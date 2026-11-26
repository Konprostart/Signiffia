package dev.signiffia.account

import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

/**
 * Authenticated ephemeral key exchange (ECDH over P-256) for device pairing.
 *
 * The exchange is authenticated by an out-of-band confirmation code shown on both screens:
 * both devices hash (their own shared secret || nonce) and the user confirms the code is
 * identical, which defeats a man-in-the-middle. The derived key wraps the Master Vault Key
 * for the new device, so the MVK itself never crosses the channel in the clear.
 *
 * Pure JVM, unit tested.
 */
object EphemeralKeyExchange {

    private const val CURVE = "secp256r1"
    private val DOMAIN = "signiffia-enroll-v1".toByteArray(StandardCharsets.US_ASCII)
    private val rng = SecureRandom()

    fun generateKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec(CURVE), rng)
        return generator.generateKeyPair()
    }

    /** Encodes a public key in X.509 SubjectPublicKeyInfo form (portable byte array). */
    fun encodePublicKey(publicKey: java.security.PublicKey): ByteArray = publicKey.encoded

    fun decodePublicKey(bytes: ByteArray): java.security.PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))

    /** Derives the raw ECDH shared secret (P-256 → 32 bytes). */
    fun sharedSecret(ourPrivate: java.security.PrivateKey, theirPublic: java.security.PublicKey): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(ourPrivate)
        agreement.doPhase(theirPublic, true)
        return agreement.generateSecret()
    }

    /** Derives a 256-bit AES wrapping key from the shared secret, domain-separated. */
    fun deriveWrappingKey(sharedSecret: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(DOMAIN)
        digest.update(sharedSecret)
        return digest.digest()
    }

    /**
     * Short confirmation code derived from the shared secret and nonce, shown on both
     * screens for out-of-band verification. Uses only digits to stay human friendly.
     */
    fun confirmationCode(sharedSecret: ByteArray, nonce: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(DOMAIN)
        digest.update(sharedSecret)
        digest.update(nonce)
        val bytes = digest.digest()
        val value = ((bytes[0].toLong() and 0xFF) shl 24) or
            ((bytes[1].toLong() and 0xFF) shl 16) or
            ((bytes[2].toLong() and 0xFF) shl 8) or
            (bytes[3].toLong() and 0xFF)
        return String.format("%06d", (value and 0x7FFFFFFF) % 1_000_000)
    }
}
