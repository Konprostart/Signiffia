package dev.signiffia.account

import java.security.SecureRandom
import java.util.Base64

/** A single-use, short-lived enrollment ticket issued for QR / manual device pairing. */
data class EnrollmentTicket(
    val ticketId: String,
    val expiresAtMillis: Long,
    /** The enrolling device's ECDH public key (opaque; the server only relays it). */
    val devicePublicKey: ByteArray,
    /** Fresh nonce used in the pairing confirmation, to bind the exchange and stop replay. */
    val nonce: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is EnrollmentTicket &&
            ticketId == other.ticketId &&
            expiresAtMillis == other.expiresAtMillis &&
            devicePublicKey.contentEquals(other.devicePublicKey) &&
            nonce.contentEquals(other.nonce)

    override fun hashCode(): Int {
        var result = ticketId.hashCode()
        result = 31 * result + expiresAtMillis.hashCode()
        result = 31 * result + devicePublicKey.contentHashCode()
        result = 31 * result + nonce.contentHashCode()
        return result
    }
}

/**
 * Pure-JVM logic for device-enrollment tickets. The actual server is responsible for
 * storing tickets and marking them consumed; this object holds the deterministic rules so
 * they can be unit tested: tickets are random, single-use and expire.
 */
object EnrollmentToken {

    const val TICKET_ID_BYTES = 16
    const val NONCE_BYTES = 16
    const val DEFAULT_TTL_MILLIS = 5 * 60 * 1000L // 5 minutes

    private val rng = SecureRandom()

    fun generateTicketId(): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(TICKET_ID_BYTES).also { rng.nextBytes(it) })

    fun generateNonce(): ByteArray = ByteArray(NONCE_BYTES).also { rng.nextBytes(it) }

    fun isExpired(ticket: EnrollmentTicket, nowMillis: Long): Boolean =
        nowMillis > ticket.expiresAtMillis

    fun isConsumed(consumedTicketIds: Set<String>, ticket: EnrollmentTicket): Boolean =
        consumedTicketIds.contains(ticket.ticketId)

    /**
     * True only when the ticket is still valid, unexpired and not yet consumed. Replay of
     * the same ticket, or use after the TTL, is always rejected.
     */
    fun authorize(consumedTicketIds: Set<String>, ticket: EnrollmentTicket, nowMillis: Long): Boolean =
        !isExpired(ticket, nowMillis) && !isConsumed(consumedTicketIds, ticket)
}
