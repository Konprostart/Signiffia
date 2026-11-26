package dev.signiffia.sync

/** One device registered to the account (server metadata only — no secrets). */
data class DeviceInfo(
    val deviceId: String,
    val name: String,
    val platform: String,
    val createdAt: Long,
    val lastSeenAt: Long,
    val revoked: Boolean,
)

/** The encrypted vault snapshot as the server stores it (ciphertext + metadata only). */
data class VaultSnapshot(
    val vaultVersion: Long,
    val epoch: Long,
    val deviceId: String,
    val createdAt: Long,
    val recordsSha256: String,
    /** AES-GCM nonce. */
    val nonce: ByteArray,
    /** AES-GCM ciphertext of the encoded entries. */
    val ciphertext: ByteArray,
    /** MVK wrapped with the recovery key — server never sees the MVK or the recovery key. */
    val wrappedMvkRecovery: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is VaultSnapshot &&
            vaultVersion == other.vaultVersion &&
            epoch == other.epoch &&
            deviceId == other.deviceId &&
            createdAt == other.createdAt &&
            recordsSha256 == other.recordsSha256 &&
            nonce.contentEquals(other.nonce) &&
            ciphertext.contentEquals(other.ciphertext) &&
            wrappedMvkRecovery.contentEquals(other.wrappedMvkRecovery)

    override fun hashCode(): Int {
        var result = vaultVersion.hashCode()
        result = 31 * result + epoch.hashCode()
        result = 31 * result + deviceId.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + recordsSha256.hashCode()
        result = 31 * result + nonce.contentHashCode()
        result = 31 * result + ciphertext.contentHashCode()
        result = 31 * result + wrappedMvkRecovery.contentHashCode()
        return result
    }
}

/** Server-authoritative vault metadata (version + epoch), fetched before an upload. */
data class VaultMeta(
    val vaultVersion: Long,
    val epoch: Long,
)

/** An authenticated account session. */
data class SessionInfo(
    val userId: String,
    val sessionToken: String,
    val deviceId: String,
)

/** An enrollment ticket returned by the server for a pairing attempt. */
data class EnrollTicket(
    val ticketId: String,
    val expiresAtMillis: Long,
    /** Fresh nonce bound to this ticket, used in the out-of-band confirmation code. */
    val nonce: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is EnrollTicket &&
            ticketId == other.ticketId &&
            expiresAtMillis == other.expiresAtMillis &&
            nonce.contentEquals(other.nonce)

    override fun hashCode(): Int {
        var result = ticketId.hashCode()
        result = 31 * result + expiresAtMillis.hashCode()
        result = 31 * result + nonce.contentHashCode()
        return result
    }
}

/** A server-side error, surfaced without ever including plaintext secrets. */
class SyncException(message: String, val code: Int = 0) : Exception(message)
