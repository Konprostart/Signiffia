package dev.signiffia.account

import dev.signiffia.vault.SealedBox

/**
 * A device-bound wrapping key. It is used to wrap/unwrap the Master Vault Key for local
 * storage; the Android implementation backs it with the Android Keystore (non-exportable,
 * requires fresh user authentication), so the MVK never rests on disk in plaintext and
 * unwrapping always needs an explicit user gesture.
 *
 * This interface is pure JVM; the concrete Android implementation lives in
 * [AndroidDeviceKey]. The device key is deliberately NOT the only recovery path — the
 * independent recovery key is.
 */
interface DeviceKey {
    val isAvailable: Boolean

    /** Wraps the MVK with this device's key (ciphertext only). */
    fun wrapMvk(mvk: ByteArray): SealedBox

    /** Unwraps the MVK; throws when the device key or user authentication is unavailable. */
    fun unwrapMvk(box: SealedBox): ByteArray
}
