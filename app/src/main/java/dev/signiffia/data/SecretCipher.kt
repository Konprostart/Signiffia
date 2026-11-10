package dev.signiffia.data

/**
 * Encrypts small secrets (keystore passwords) with a device bound key.
 *
 * The Android implementation is backed by the Android Keystore (AES-256-GCM). Keeping
 * this behind an interface means [KeyRepository] stays plain JVM code and is unit tested.
 */
interface SecretCipher {
    /** True when encrypt/decrypt can be used at all on this device. */
    val isAvailable: Boolean

    fun encrypt(plaintext: String): String

    /** Returns null when the secret cannot be decrypted (e.g. device key was lost). */
    fun decrypt(token: String): String?
}

/** A keystore password stored on disk can no longer be decrypted on this device. */
class VaultLockedException(message: String, cause: Throwable? = null) : Exception(message, cause)
