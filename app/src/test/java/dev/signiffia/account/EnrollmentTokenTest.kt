package dev.signiffia.account

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrollmentTokenTest {

    private fun ticket(expiresAt: Long = 1_000_000L): EnrollmentTicket =
        EnrollmentTicket(
            ticketId = EnrollmentToken.generateTicketId(),
            expiresAtMillis = expiresAt,
            devicePublicKey = ByteArray(65) { it.toByte() },
            nonce = EnrollmentToken.generateNonce(),
        )

    @Test
    fun `fresh ticket is authorized`() {
        val t = ticket(expiresAt = 2_000_000L)
        assertTrue(EnrollmentToken.authorize(emptySet(), t, nowMillis = 1_000_000L))
    }

    @Test
    fun `expired ticket is rejected`() {
        val t = ticket(expiresAt = 1_000_000L)
        assertTrue(EnrollmentToken.isExpired(t, nowMillis = 1_000_001L))
        assertFalse(EnrollmentToken.authorize(emptySet(), t, nowMillis = 1_000_001L))
    }

    @Test
    fun `exactly at expiry is still valid`() {
        val t = ticket(expiresAt = 1_000_000L)
        assertFalse(EnrollmentToken.isExpired(t, nowMillis = 1_000_000L))
        assertTrue(EnrollmentToken.authorize(emptySet(), t, nowMillis = 1_000_000L))
    }

    @Test
    fun `consumed ticket is rejected (replay protection)`() {
        val t = ticket(expiresAt = 2_000_000L)
        assertFalse(EnrollmentToken.authorize(setOf(t.ticketId), t, nowMillis = 1_000_000L))
    }

    @Test
    fun `ticket ids are unique and non-empty`() {
        val a = EnrollmentToken.generateTicketId()
        val b = EnrollmentToken.generateTicketId()
        assertTrue(a.isNotBlank())
        assertTrue(b.isNotBlank())
        assertFalse(a == b)
    }
}
