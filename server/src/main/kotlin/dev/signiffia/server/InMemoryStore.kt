package dev.signiffia.server

import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory [Store]. Used for local development and for the server's unit tests;
 * it is NOT persistent and must not be used in production.
 */
class InMemoryStore : Store {

    private val usersById = ConcurrentHashMap<String, UserRecord>()
    private val usersByName = ConcurrentHashMap<String, UserRecord>()
    private val sessions = ConcurrentHashMap<String, SessionRecord>()
    private val devices = ConcurrentHashMap<String, DeviceRecord>()
    private val vaults = ConcurrentHashMap<String, VaultRecord>()
    private val epochs = ConcurrentHashMap<String, Long>()
    private val tickets = ConcurrentHashMap<String, EnrollTicketRecord>()
    private val webauthnCredentials = ConcurrentHashMap<String, WebAuthnCredentialRecord>()
    private val webauthnChallenges = ConcurrentHashMap<String, WebAuthnChallengeRecord>()

    override fun findUserByName(username: String): UserRecord? = usersByName[username]

    override fun findUserById(userId: String): UserRecord? = usersById[userId]

    override fun insertUser(user: UserRecord): Boolean {
        val existing = usersByName.putIfAbsent(user.username, user)
        if (existing != null) return false
        usersById[user.userId] = user
        return true
    }

    override fun insertSession(tokenHash: String, session: SessionRecord) {
        sessions[tokenHash] = session
    }

    override fun findSession(tokenHash: String): SessionRecord? = sessions[tokenHash]

    override fun deleteSession(tokenHash: String) {
        sessions.remove(tokenHash)
    }

    override fun insertDevice(device: DeviceRecord) {
        devices[device.deviceId] = device
    }

    override fun findDevice(deviceId: String): DeviceRecord? = devices[deviceId]

    override fun touchDevice(deviceId: String, lastSeenAt: Long) {
        devices[deviceId]?.let { devices[deviceId] = it.copy(lastSeenAt = lastSeenAt) }
    }

    override fun setDeviceRevoked(deviceId: String, revoked: Boolean) {
        devices[deviceId]?.let { devices[deviceId] = it.copy(revoked = revoked) }
    }

    override fun listDevices(userId: String): List<DeviceRecord> =
        devices.values.filter { it.userId == userId }

    override fun getVault(userId: String): VaultRecord? = vaults[userId]

    override fun putVault(userId: String, vault: VaultRecord) {
        vaults[userId] = vault
    }

    override fun getEpoch(userId: String): Long = epochs[userId] ?: 1L

    override fun setEpoch(userId: String, epoch: Long) {
        epochs[userId] = epoch
    }

    override fun insertTicket(ticket: EnrollTicketRecord) {
        tickets[ticket.ticketId] = ticket
    }

    override fun findTicket(ticketId: String): EnrollTicketRecord? = tickets[ticketId]

    override fun markTicketUsed(ticketId: String) {
        tickets[ticketId]?.let { tickets[ticketId] = it.copy(used = true) }
    }

    override fun insertWebAuthnCredential(credential: WebAuthnCredentialRecord) {
        webauthnCredentials[credential.credentialId] = credential
    }

    override fun findWebAuthnCredential(credentialId: String): WebAuthnCredentialRecord? =
        webauthnCredentials[credentialId]

    override fun listWebAuthnCredentials(userId: String): List<WebAuthnCredentialRecord> =
        webauthnCredentials.values.filter { it.userId == userId }

    override fun updateWebAuthnSignCount(credentialId: String, signCount: Long) {
        webauthnCredentials[credentialId]?.let { webauthnCredentials[credentialId] = it.copy(signCount = signCount) }
    }

    override fun insertWebAuthnChallenge(challenge: WebAuthnChallengeRecord) {
        webauthnChallenges[challenge.challengeId] = challenge
    }

    override fun findWebAuthnChallenge(challengeId: String): WebAuthnChallengeRecord? =
        webauthnChallenges[challengeId]

    override fun deleteWebAuthnChallenge(challengeId: String) {
        webauthnChallenges.remove(challengeId)
    }
}
