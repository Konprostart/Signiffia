package dev.signiffia.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.signiffia.vault.SealedBox
import dev.signiffia.vault.VaultCrypto
import dev.signiffia.vault.VaultCryptoException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [DeviceKey] backed by the Android Keystore.
 *
 * The key is AES-256-GCM, non-exportable (hardware-backed where available) and requires
 * fresh user authentication (biometric or device credential) for every use, with a short
 * validity window so a recently-authorized signing session can proceed. Because the key
 * cannot be exported, the Master Vault Key is wrapped by encrypting it *with* the Keystore
 * key (the same primitive [VaultCrypto] uses), never by exporting the key bytes.
 *
 * This enforces "signing operations require explicit user authorization": unwrapping the MVK
 * (the first step of signing) triggers a biometric/PIN prompt.
 */
class AndroidDeviceKey(context: Context) : DeviceKey {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    override val isAvailable: Boolean
        get() = runCatching { obtainKey() }.isSuccess

    override fun wrapMvk(mvk: ByteArray): SealedBox {
        if (mvk.size != VaultCrypto.KEY_BYTES) throw VaultCryptoException("Neispravan MVK.")
        val cipher = Cipher.getInstance(VaultCrypto.TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, obtainKey())
        val ciphertext = cipher.doFinal(mvk)
        return SealedBox(nonce = cipher.iv, ciphertext = ciphertext)
    }

    override fun unwrapMvk(box: SealedBox): ByteArray {
        val cipher = Cipher.getInstance(VaultCrypto.TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, obtainKey(), GCMParameterSpec(VaultCrypto.TAG_BITS, box.nonce))
        return cipher.doFinal(box.ciphertext)
    }

    @Synchronized
    private fun obtainKey(): SecretKey {
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationValidityDurationSeconds(AUTH_VALIDITY_SECONDS)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "signiffia.device.v1"
        private const val AUTH_VALIDITY_SECONDS = 60

        fun create(context: Context): AndroidDeviceKey =
            AndroidDeviceKey(context.applicationContext)
    }
}
