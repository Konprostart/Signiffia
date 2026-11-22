package dev.signiffia.ui.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockModelTest {

    @Test
    fun defaultConfigIsOffWithThirtySecondDelay() {
        val config = LockConfig()
        assertEquals(LockMode.OFF, config.mode)
        assertEquals(AutoLockDelay.THIRTY_SECONDS, config.autoLockDelay)
        assertFalse(config.enabled)
    }

    @Test
    fun configIsEnabledOnlyWhenModeIsNotOff() {
        assertFalse(LockConfig(LockMode.OFF).enabled)
        assertTrue(LockConfig(LockMode.DEVICE_CREDENTIAL).enabled)
        assertTrue(LockConfig(LockMode.BIOMETRIC_OR_CREDENTIAL).enabled)
    }

    @Test
    fun neverRelocksWhenDisabled() {
        val config = LockConfig(LockMode.OFF, AutoLockDelay.IMMEDIATE)
        assertFalse(shouldRelockOnForeground(config, backgroundedAtMillis = 1_000L, nowMillis = 100_000L))
    }

    @Test
    fun neverRelocksWhenNotYetBackgrounded() {
        val config = LockConfig(LockMode.DEVICE_CREDENTIAL, AutoLockDelay.IMMEDIATE)
        assertFalse(shouldRelockOnForeground(config, backgroundedAtMillis = 0L, nowMillis = 1_000_000L))
    }

    @Test
    fun relocksImmediatelyWhenDelayIsZero() {
        val config = LockConfig(LockMode.DEVICE_CREDENTIAL, AutoLockDelay.IMMEDIATE)
        assertTrue(shouldRelockOnForeground(config, backgroundedAtMillis = 100L, nowMillis = 100L))
    }

    @Test
    fun keepsUnlockedWhenElapsedIsBelowDelay() {
        val config = LockConfig(LockMode.BIOMETRIC_OR_CREDENTIAL, AutoLockDelay.THIRTY_SECONDS)
        val backgroundedAt = 1_000L
        val now = backgroundedAt + 29_999L
        assertFalse(shouldRelockOnForeground(config, backgroundedAt, now))
    }

    @Test
    fun relocksAtTheExactDelayBoundary() {
        val config = LockConfig(LockMode.BIOMETRIC_OR_CREDENTIAL, AutoLockDelay.ONE_MINUTE)
        val backgroundedAt = 5_000L
        val now = backgroundedAt + 60_000L
        assertTrue(shouldRelockOnForeground(config, backgroundedAt, now))
    }

    @Test
    fun relocksAfterTheDelay() {
        val config = LockConfig(LockMode.DEVICE_CREDENTIAL, AutoLockDelay.FIVE_MINUTES)
        val backgroundedAt = 5_000L
        val now = backgroundedAt + 300_001L
        assertTrue(shouldRelockOnForeground(config, backgroundedAt, now))
    }

    @Test
    fun autoLockDelayMillisAreCorrect() {
        assertEquals(0L, AutoLockDelay.IMMEDIATE.millis)
        assertEquals(30_000L, AutoLockDelay.THIRTY_SECONDS.millis)
        assertEquals(60_000L, AutoLockDelay.ONE_MINUTE.millis)
        assertEquals(300_000L, AutoLockDelay.FIVE_MINUTES.millis)
    }

    @Test
    fun modeAndDelayNamesRoundTripForPersistence() {
        // SharedPreferences stores enum .name(); valueOf must stay stable for upgrades.
        LockMode.values().forEach { mode -> assertEquals(mode, LockMode.valueOf(mode.name)) }
        AutoLockDelay.values().forEach { delay -> assertEquals(delay, AutoLockDelay.valueOf(delay.name)) }
    }
}
