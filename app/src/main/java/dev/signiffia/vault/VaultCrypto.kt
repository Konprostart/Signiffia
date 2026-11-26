package dev.signiffia.vault

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Raised for expected, user-facing vault crypto failures (e.g. wrong key / tampered data). */
class VaultCryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * A sealed AEAD box: the nonce plus the AES-GCM ciphertext (and its 128-bit tag, appended
 * by the JCE provider). Everything else (version, AAD) is carried by the caller so the box
 * stays a minimal, testable value object.
 */
data class SealedBox(
    val nonce: ByteArray,
    val ciphertext: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is SealedBox && nonce.contentEquals(other.nonce) && ciphertext.contentEquals(other.ciphertext)

    override fun hashCode(): Int = 31 * nonce.contentHashCode() + ciphertext.contentHashCode()

    /** Serialises to `nonce || ciphertext` (the nonce length is fixed, so it is unambiguous). */
    fun toBytes(): ByteArray = nonce + ciphertext

    companion object {
        /** Deserialises a box produced by [toBytes]. */
        fun fromBytes(bytes: ByteArray): SealedBox {
            if (bytes.size < VaultCrypto.NONCE_BYTES) {
                throw VaultCryptoException("Neispravan šifrovan zapis.")
            }
            return SealedBox(
                nonce = bytes.copyOfRange(0, VaultCrypto.NONCE_BYTES),
                ciphertext = bytes.copyOfRange(VaultCrypto.NONCE_BYTES, bytes.size),
            )
        }
    }
}

/**
 * AES-256-GCM authenticated encryption and key wrapping for the encrypted vault.
 *
 * Design invariants (all enforced here and unit tested):
 *  - Every encryption uses a fresh, cryptographically random 12-byte nonce.
 *  - Every call binds a caller-supplied AAD, so a ciphertext can never be replayed under a
 *    different purpose / vault version / epoch (object substitution and rollback defence).
 *  - Wrapping reuses the exact same AEAD primitive (no separate AES-KWP provider needed).
 *  - Key comparison uses constant-time equality where it matters.
 *
 * This class is pure JVM: it has no Android dependency and is fully unit tested.
 */
object VaultCrypto {

    const val KEY_BYTES = 32 // 256-bit
    const val NONCE_BYTES = 12
    const val TAG_BITS = 128
    const val BOX_VERSION = 1
    const val TRANSFORMATION = "AES/GCM/NoPadding"

    private val random = SecureRandom()

    /** Generates a fresh 256-bit key (the Master Vault Key or a wrapping key). */
    fun generateKey(): ByteArray = ByteArray(KEY_BYTES).also { random.nextBytes(it) }

    /** Generates a fresh 12-byte nonce. */
    fun generateNonce(): ByteArray = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }

    fun encrypt(key: ByteArray, plaintext: ByteArray, aad: ByteArray): SealedBox {
        requireKey(key)
        val nonce = generateNonce()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plaintext)
        return SealedBox(nonce, ciphertext)
    }

    fun decrypt(key: ByteArray, box: SealedBox, aad: ByteArray): ByteArray {
        requireKey(key)
        if (box.nonce.size != NONCE_BYTES) {
            throw VaultCryptoException("Neispravan nonce u šifrovanom zapisu.")
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, box.nonce))
        cipher.updateAAD(aad)
        return try {
            cipher.doFinal(box.ciphertext)
        } catch (e: AEADBadTagException) {
            throw VaultCryptoException("Provera integriteta nije uspela — pogrešan ključ ili izmenjen podatak.", e)
        } catch (e: Throwable) {
            throw VaultCryptoException("Zapis nije moguće dešifrovati: ${e.message}", e)
        }
    }

    /** Wraps [keyToWrap] with [kek], binding the operation to [purpose] via AAD. */
    fun wrapKey(kek: ByteArray, keyToWrap: ByteArray, purpose: String): SealedBox =
        encrypt(kek, keyToWrap, aad(purpose))

    fun unwrapKey(kek: ByteArray, box: SealedBox, purpose: String): ByteArray =
        decrypt(kek, box, aad(purpose))

    /**
     * Builds an unambiguous AAD by length-prefixing each field, so the tuple
     * ("a","bc") can never collide with ("ab","c").
     */
    fun aad(vararg fields: String): ByteArray {
        val out = ByteArrayOutputStream()
        for (field in fields) {
            val bytes = field.toByteArray(StandardCharsets.UTF_8)
            out.write(intToBytes(bytes.size))
            out.write(bytes)
        }
        return out.toByteArray()
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    private fun requireKey(key: ByteArray) {
        if (key.size != KEY_BYTES) {
            throw VaultCryptoException("Ključ mora imati $KEY_BYTES bajtova (256 bita).")
        }
    }

    private fun intToBytes(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte()
    )

    private fun ByteArray.contentHashCode(): Int = Arrays.hashCode(this)
}
