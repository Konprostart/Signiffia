package dev.signiffia.ui.lock

import java.util.concurrent.atomic.AtomicInteger

/**
 * Tracks system-UI flows that the app launches itself (SAF document pickers, the
 * keyguard confirmation screen and the biometric prompt). While such a flow is active the
 * activity is only briefly backgrounded by its own request, so the auto-lock must NOT
 * treat it as "the user left the app".
 *
 * Call [enter] immediately before launching one of these flows and [exit] when its result
 * arrives (in the ActivityResult / authentication callback). A counter (rather than a
 * boolean) tolerates nested or overlapping flows.
 */
object AppLockGate {
    private val active = AtomicInteger(0)

    val isExternalFlowActive: Boolean get() = active.get() > 0

    fun enter() {
        active.incrementAndGet()
    }

    fun exit() {
        active.decrementAndGet()
    }
}
