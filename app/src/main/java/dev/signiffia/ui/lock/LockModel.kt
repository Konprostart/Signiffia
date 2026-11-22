package dev.signiffia.ui.lock

/**
 * Pure, Android-free model for the optional app lock. Kept in its own file so the
 * decision logic can be unit-tested on the JVM without any Android dependency.
 */

/** How the app asks the user to unlock. */
enum class LockMode {
    /** No app lock — Signiffia behaves exactly as before. */
    OFF,

    /** Unlock with the device PIN / pattern / password (KeyguardManager confirm). */
    DEVICE_CREDENTIAL,

    /** Unlock with biometrics, with the device PIN/pattern/password as fallback. */
    BIOMETRIC_OR_CREDENTIAL,
}

/** How long the app may stay unlocked while it is in the background. */
enum class AutoLockDelay(val millis: Long) {
    IMMEDIATE(0L),
    THIRTY_SECONDS(30_000L),
    ONE_MINUTE(60_000L),
    FIVE_MINUTES(300_000L),
}

/** Persisted app-lock configuration. */
data class LockConfig(
    val mode: LockMode = LockMode.OFF,
    val autoLockDelay: AutoLockDelay = AutoLockDelay.THIRTY_SECONDS,
) {
    val enabled: Boolean get() = mode != LockMode.OFF
}

/**
 * Decides whether the app must re-lock when it returns to the foreground.
 *
 * @param backgroundedAtMillis monotonic elapsed-realtime timestamp recorded when the app
 *   went to the background (0 means "not backgrounded yet", e.g. a cold start).
 * @param nowMillis current monotonic elapsed-realtime timestamp.
 */
fun shouldRelockOnForeground(
    config: LockConfig,
    backgroundedAtMillis: Long,
    nowMillis: Long,
): Boolean {
    if (!config.enabled) return false
    if (backgroundedAtMillis <= 0L) return false
    val elapsed = nowMillis - backgroundedAtMillis
    return elapsed >= config.autoLockDelay.millis
}
