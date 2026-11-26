package dev.signiffia.server

/**
 * Business logic for the untrusted Signiffia sync server, independent of any HTTP framework
 * so it can be unit tested in full against an in-memory [Store].
 *
 * Security invariants enforced here:
 *  - Only the SHA-256 hash of a session token is persisted, so a database leak exposes no
 *    usable bearer tokens.
 *  - The account verifier is compared in constant time and the raw password never arrives.
 *  - The vault epoch is server-monotonic (bumped only on revocation) and the vault version is
 *    strictly increasing; stale-epoch or stale-version uploads are rejected.
 *  - Revoked devices are rejected at authentication, which stops future synchronization.
 *  - Enrollment tickets are single-use with a short TTL.
 *
 * None of these rules rely on the server being trusted: it only ever sees ciphertext.
 */
class ServerCore(
    private val store: Store,
    private val loginLimiter: RateLimiter = RateLimiter(maxAttempts = 5, windowMillis = 60_000),
    private val registerLimiter: RateLimiter = RateLimiter(maxAttempts = 10, windowMillis = 60_000),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    companion object {
        const val SESSION_TTL_MILLIS: Long = 30L * 24 * 3600 * 1000 // 30 days
        const val ENROLL_TTL_MILLIS: Long = 5 * 60 * 1000L // 5 minutes
    }

    /** Outcome of a vault upload attempt. */
    sealed interface VaultResult {
        object Accepted : VaultResult
        data class Rejected(val reason: String, val currentVersion: Long, val currentEpoch: Long) : VaultResult
    }

    // ------------------------------------------------------------- auth

    /** Returns the account's Argon2id salt, or null when the user does not exist. */
    fun fetchSalt(username: String): ByteArray? = store.findUserByName(username)?.salt

    /** Registers a new account. Returns null on success, or an error message. */
    fun register(username: String, salt: ByteArray, verifier: ByteArray): String? {
        if (username.isBlank()) return "Korisničko ime ne sme biti prazno."
        if (verifier.size != 32) return "Neispravan verifikator."
        if (salt.isEmpty()) return "Neispravan salt."
        val user = UserRecord(Security.randomId(), username, verifier, salt)
        return if (store.insertUser(user)) null else "Korisnik već postoji."
    }

    /**
     * Authenticates with a password verifier. Returns a fresh session (userId + bearer token
     * + new deviceId) or null on failure. The presented verifier is compared in constant time.
     */
    fun login(username: String, verifier: ByteArray): SessionResult? {
        val user = store.findUserByName(username)
        if (user == null || !Security.constantTimeEquals(user.verifier, verifier)) return null
        val token = Security.generateSessionToken()
        val deviceId = Security.randomId()
        val now = clock()
        store.insertSession(
            Security.tokenHash(token),
            SessionRecord(user.userId, deviceId, now, now + SESSION_TTL_MILLIS),
        )
        store.insertDevice(
            DeviceRecord(deviceId, user.userId, "Novi uređaj", "android", now, now, revoked = false),
        )
        return SessionResult(user.userId, token, deviceId)
    }

    /** Validates a bearer token, returning the live session, or null. */
    fun authenticate(token: String?): AuthenticatedSession? {
        val raw = token?.removePrefix("Bearer ") ?: return null
        if (raw.isBlank()) return null
        val session = store.findSession(Security.tokenHash(raw)) ?: return null
        if (session.expiresAt < clock()) return null
        val device = store.findDevice(session.deviceId) ?: return null
        if (device.revoked) return null
        store.touchDevice(session.deviceId, clock())
        return AuthenticatedSession(session.userId, session.deviceId)
    }

    /** The account username for an authenticated session, or null. */
    fun usernameOf(session: AuthenticatedSession): String? = store.findUserById(session.userId)?.username

    // ------------------------------------------------------------- vault

    /** Current vault metadata (version + epoch); version 0 means "no vault yet". */
    fun vaultMeta(session: AuthenticatedSession): VaultMeta {
        val current = store.getVault(session.userId)
        return VaultMeta(
            vaultVersion = current?.vaultVersion ?: 0L,
            epoch = store.getEpoch(session.userId),
        )
    }

    /**
     * Accepts a vault upload only when its version is strictly newer than the stored one and
     * its epoch exactly matches the server's current epoch. This is the server-side rollback,
     * replay and stale-epoch defence (the client additionally binds version + epoch into the
     * AES-GCM AAD, so a substituted object can never decrypt).
     */
    fun putVault(session: AuthenticatedSession, incoming: VaultRecord): VaultResult {
        val current = store.getVault(session.userId)
        if (current != null && incoming.vaultVersion <= current.vaultVersion) {
            return VaultResult.Rejected(
                "Zastarela verzija trezora.",
                current.vaultVersion,
                store.getEpoch(session.userId),
            )
        }
        val epoch = store.getEpoch(session.userId)
        if (incoming.epoch != epoch) {
            return VaultResult.Rejected(
                "Zastarela epoha (uređaj je možda povučen).",
                current?.vaultVersion ?: 0L,
                epoch,
            )
        }
        store.putVault(session.userId, incoming)
        return VaultResult.Accepted
    }

    fun getVault(session: AuthenticatedSession): VaultRecord? = store.getVault(session.userId)

    // ------------------------------------------------------------- devices

    fun listDevices(session: AuthenticatedSession): List<DeviceRecord> =
        store.listDevices(session.userId)

    /** Revokes a device and bumps the vault epoch. Returns the new epoch. */
    fun revokeDevice(session: AuthenticatedSession, deviceId: String): Long? {
        val device = store.findDevice(deviceId) ?: return null
        if (device.userId != session.userId) return null
        store.setDeviceRevoked(deviceId, true)
        val newEpoch = store.getEpoch(session.userId) + 1
        store.setEpoch(session.userId, newEpoch)
        return newEpoch
    }

    // ------------------------------------------------------------- enrollment

    /** Begins pairing: creates a single-use, short-lived ticket carrying the device public key. */
    fun enrollBegin(session: AuthenticatedSession, devicePublicKey: ByteArray): EnrollTicketInfo {
        val ticketId = Security.randomId()
        val nonce = Security.randomBytes(16)
        val now = clock()
        store.insertTicket(
            EnrollTicketRecord(
                ticketId = ticketId,
                userId = session.userId,
                devicePublicKey = devicePublicKey,
                nonce = nonce,
                expiresAt = now + ENROLL_TTL_MILLIS,
                used = false,
            )
        )
        return EnrollTicketInfo(ticketId, now + ENROLL_TTL_MILLIS, nonce)
    }

    /** Confirms pairing by consuming a valid, unexpired ticket (replay is rejected). */
    fun enrollConfirm(session: AuthenticatedSession, ticketId: String): Boolean {
        val ticket = store.findTicket(ticketId) ?: return false
        if (ticket.userId != session.userId) return false
        if (ticket.used) return false
        if (ticket.expiresAt < clock()) return false
        store.markTicketUsed(ticketId)
        return true
    }

    // ------------------------------------------------------------- rate limiting
    // The key is typically the client IP (or X-Forwarded-For when behind a proxy), so a
    // single source cannot brute-force credentials regardless of the username it tries.

    fun allowLogin(key: String): Boolean = loginLimiter.allow(key)
    fun allowRegister(key: String): Boolean = registerLimiter.allow(key)
    fun resetLogin(key: String) = loginLimiter.reset(key)
}

data class SessionResult(val userId: String, val sessionToken: String, val deviceId: String)
data class AuthenticatedSession(val userId: String, val deviceId: String)
data class VaultMeta(val vaultVersion: Long, val epoch: Long)
data class EnrollTicketInfo(val ticketId: String, val expiresAtMillis: Long, val nonce: ByteArray)
