package dev.signiffia.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimiterTest {

    @Test
    fun allowsUpToMaxWithinWindow() {
        val limiter = RateLimiter(maxAttempts = 3, windowMillis = 60_000, clock = { 1000L })
        assertTrue(limiter.allow("k"))
        assertTrue(limiter.allow("k"))
        assertTrue(limiter.allow("k"))
        assertFalse(limiter.allow("k")) // 4th attempt blocked
    }

    @Test
    fun windowSlidesAfterExpiry() {
        var now = 1000L
        val limiter = RateLimiter(maxAttempts = 2, windowMillis = 1000, clock = { now })
        assertTrue(limiter.allow("k"))
        assertTrue(limiter.allow("k"))
        assertFalse(limiter.allow("k"))
        now += 1001
        assertTrue(limiter.allow("k")) // new window
    }

    @Test
    fun keysAreIndependent() {
        val limiter = RateLimiter(maxAttempts = 1, windowMillis = 60_000, clock = { 1000L })
        assertTrue(limiter.allow("a"))
        assertTrue(limiter.allow("b"))
        assertFalse(limiter.allow("a"))
    }
}
