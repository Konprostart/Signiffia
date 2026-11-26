package dev.signiffia.server

import java.util.concurrent.ConcurrentHashMap

/**
 * A minimal in-memory sliding-window rate limiter, keyed by an arbitrary string (typically
 * `username` for login and `ip` for registration). It is best-effort and per-instance: a
 * multi-instance production deployment should add a shared limiter (e.g. in the reverse
 * proxy or via Redis), but this protects a single node against credential guessing.
 */
class RateLimiter(
    private val maxAttempts: Int,
    private val windowMillis: Long,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private data class Window(val start: Long, val count: Int)

    private val windows = ConcurrentHashMap<String, Window>()

    /** Records an attempt and returns true only if it is still within the allowed rate. */
    @Synchronized
    fun allow(key: String): Boolean {
        val now = clock()
        val current = windows[key]
        return if (current == null || now - current.start >= windowMillis) {
            windows[key] = Window(now, 1)
            true
        } else if (current.count < maxAttempts) {
            windows[key] = current.copy(count = current.count + 1)
            true
        } else {
            false
        }
    }

    @Synchronized
    fun reset(key: String) {
        windows.remove(key)
    }
}
