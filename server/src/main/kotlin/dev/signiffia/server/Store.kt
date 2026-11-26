package dev.signiffia.server

/**
 * Server-side records. Every record holds only ciphertext and metadata — the server never
 * holds the Master Vault Key, recovery key, private signing keys or keystore passwords in
 * plaintext.
 */

data class UserRecord(
    val userId: String,
    val username: String,
    /** Argon2id verifier (derived client-side); never the raw password. */
    val verifier: ByteArray,
    val salt: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is UserRecord && userId == other.userId && username == other.username &&
            verifier.contentEquals(other.verifier) && salt.contentEquals(other.salt)

    override fun hashCode(): Int {
        var r = userId.hashCode()
        r = 31 * r + username.hashCode()
        r = 31 * r + verifier.contentHashCode()
        r = 31 * r + salt.contentHashCode()
        return r
    }
}

/** A session is keyed by the SHA-256 hash of its bearer token, never the raw token. */
data class SessionRecord(
    val userId: String,
    val deviceId: String,
    val createdAt: Long,
    val expiresAt: Long,
)

data class DeviceRecord(
    val deviceId: String,
    val userId: String,
    val name: String,
    val platform: String,
    val createdAt: Long,
    val lastSeenAt: Long,
    val revoked: Boolean,
)

/** The encrypted vault snapshot as the server stores it (ciphertext + metadata only). */
data class VaultRecord(
    val vaultVersion: Long,
    val epoch: Long,
    val deviceId: String,
    val createdAt: Long,
    val recordsSha256: String,
    val nonce: ByteArray,
    val ciphertext: ByteArray,
    val wrappedMvkRecovery: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is VaultRecord && vaultVersion == other.vaultVersion && epoch == other.epoch &&
            deviceId == other.deviceId && createdAt == other.createdAt &&
            recordsSha256 == other.recordsSha256 && nonce.contentEquals(other.nonce) &&
            ciphertext.contentEquals(other.ciphertext) &&
            wrappedMvkRecovery.contentEquals(other.wrappedMvkRecovery)

    override fun hashCode(): Int {
        var r = vaultVersion.hashCode()
        r = 31 * r + epoch.hashCode()
        r = 31 * r + deviceId.hashCode()
        r = 31 * r + createdAt.hashCode()
        r = 31 * r + recordsSha256.hashCode()
        r = 31 * r + nonce.contentHashCode()
        r = 31 * r + ciphertext.contentHashCode()
        r = 31 * r + wrappedMvkRecovery.contentHashCode()
        return r
    }
}

/** A WebAuthn/passkey credential (the public key is inside [recordJson]; no secret here). */
data class WebAuthnCredentialRecord(
    val credentialId: String,
    val userId: String,
    /** Serialized webauthn4j CredentialRecord (attestation + client data + transports). */
    val recordJson: String,
    val signCount: Long,
    val createdAt: Long,
)

/** A one-time, short-lived WebAuthn challenge (registration or authentication). */
data class WebAuthnChallengeRecord(
    val challengeId: String,
    val userId: String?,
    val challenge: ByteArray,
    val expiresAt: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is WebAuthnChallengeRecord && challengeId == other.challengeId &&
            userId == other.userId && challenge.contentEquals(other.challenge) &&
            expiresAt == other.expiresAt

    override fun hashCode(): Int {
        var r = challengeId.hashCode()
        r = 31 * r + (userId?.hashCode() ?: 0)
        r = 31 * r + challenge.contentHashCode()
        r = 31 * r + expiresAt.hashCode()
        return r
    }
}

/** A single-use, short-lived enrollment ticket for QR/manual device pairing. */
data class EnrollTicketRecord(
    val ticketId: String,
    val userId: String,
    val devicePublicKey: ByteArray,
    val nonce: ByteArray,
    val expiresAt: Long,
    val used: Boolean,
) {
    override fun equals(other: Any?): Boolean =
        other is EnrollTicketRecord && ticketId == other.ticketId && userId == other.userId &&
            devicePublicKey.contentEquals(other.devicePublicKey) &&
            nonce.contentEquals(other.nonce) && expiresAt == other.expiresAt && used == other.used

    override fun hashCode(): Int {
        var r = ticketId.hashCode()
        r = 31 * r + userId.hashCode()
        r = 31 * r + devicePublicKey.contentHashCode()
        r = 31 * r + nonce.contentHashCode()
        r = 31 * r + expiresAt.hashCode()
        r = 31 * r + used.hashCode()
        return r
    }
}

/**
 * Persistence boundary for the Signiffia sync server. Two implementations are provided:
 * [InMemoryStore] (development and unit tests) and [PostgresStore] (production, persistent).
 *
 * All methods must be safe for concurrent use.
 */
interface Store {
    // ------------------------------------------------------------- users
    fun findUserByName(username: String): UserRecord?
    fun findUserById(userId: String): UserRecord?
    /** Inserts a user; returns false when the username is already taken. */
    fun insertUser(user: UserRecord): Boolean

    // ------------------------------------------------------------- sessions (token hash)
    fun insertSession(tokenHash: String, session: SessionRecord)
    fun findSession(tokenHash: String): SessionRecord?
    fun deleteSession(tokenHash: String)

    // ------------------------------------------------------------- devices
    fun insertDevice(device: DeviceRecord)
    fun findDevice(deviceId: String): DeviceRecord?
    fun touchDevice(deviceId: String, lastSeenAt: Long)
    fun setDeviceRevoked(deviceId: String, revoked: Boolean)
    fun listDevices(userId: String): List<DeviceRecord>

    // ------------------------------------------------------------- vault
    fun getVault(userId: String): VaultRecord?
    /** Replaces the vault snapshot; returns false if it must not be accepted (caller checks). */
    fun putVault(userId: String, vault: VaultRecord)

    // ------------------------------------------------------------- epoch (revocation)
    fun getEpoch(userId: String): Long
    fun setEpoch(userId: String, epoch: Long)

    // ------------------------------------------------------------- enrollment
    fun insertTicket(ticket: EnrollTicketRecord)
    fun findTicket(ticketId: String): EnrollTicketRecord?
    fun markTicketUsed(ticketId: String)

    // ------------------------------------------------------------- webauthn / passkey
    fun insertWebAuthnCredential(credential: WebAuthnCredentialRecord)
    fun findWebAuthnCredential(credentialId: String): WebAuthnCredentialRecord?
    fun listWebAuthnCredentials(userId: String): List<WebAuthnCredentialRecord>
    fun updateWebAuthnSignCount(credentialId: String, signCount: Long)

    fun insertWebAuthnChallenge(challenge: WebAuthnChallengeRecord)
    fun findWebAuthnChallenge(challengeId: String): WebAuthnChallengeRecord?
    fun deleteWebAuthnChallenge(challengeId: String)

    // ------------------------------------------------------------- lifecycle
    /** Verifies storage is reachable and correctly migrated (throws otherwise). */
    fun check(): Unit = Unit
}
