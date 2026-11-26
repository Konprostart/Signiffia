package dev.signiffia.vault

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * BIP39 recovery key (mnemonic) generation and validation.
 *
 * The recovery key is the ONLY independent way to regain the vault when every authorized
 * device is lost — the Android Keystore device key is deliberately never the sole recovery
 * path. The wordlist is the canonical BIP39 English list (2048 words, embedded verbatim),
 * and the checksum/derivation follow the official BIP39 specification, which is validated
 * against the published BIP39 test vectors in [RecoveryKeyTest].
 */
object RecoveryKey {

    private const val BITS_PER_WORD = 11
    private const val PBKDF2_ITERATIONS = 2048
    private const val PBKDF2_KEY_BITS = 512
    private val SUPPORTED_WORD_COUNTS = setOf(12, 15, 18, 21, 24)

    private val rng = SecureRandom()
    private val wordIndex: Map<String, Int> =
        Bip39English.words.withIndex().associate { (index, word) -> word to index }

    /** Generates a fresh 24-word recovery mnemonic (256-bit entropy). */
    fun generate(wordCount: Int = 24): String {
        require(wordCount in SUPPORTED_WORD_COUNTS) { "Nepodržan broj reči: $wordCount." }
        val entropyBytes = (wordCount / 3) * 4 // 12->16, 15->20, 18->24, 21->28, 24->32
        val entropy = ByteArray(entropyBytes).also { rng.nextBytes(it) }
        return fromEntropy(entropy)
    }

    /** Encodes raw entropy (16..32 bytes, multiple of 4) into a BIP39 mnemonic. */
    fun fromEntropy(entropy: ByteArray): String {
        require(entropy.size in 16..32 && entropy.size % 4 == 0) { "Neispravna dužina entropije." }
        val entBits = entropy.size * 8
        val csBits = entBits / 32
        val totalBits = entBits + csBits
        val hash = sha256(entropy)
        val indices = ArrayList<Int>(totalBits / BITS_PER_WORD)
        var offset = 0
        while (offset < totalBits) {
            indices += readBits(entropy, hash, offset, BITS_PER_WORD)
            offset += BITS_PER_WORD
        }
        return indices.joinToString(" ") { Bip39English.words[it] }
    }

    /** Validates a mnemonic (wordlist + checksum) and returns its entropy, or throws. */
    fun toEntropy(mnemonic: String): ByteArray {
        val words = mnemonic.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size !in SUPPORTED_WORD_COUNTS) {
            throw VaultCryptoException("Recovery ključ mora imati 12, 15, 18, 21 ili 24 reči.")
        }
        val indices = words.map { word ->
            wordIndex[word] ?: throw VaultCryptoException("Recovery ključ sadrži nepoznatu reč.")
        }
        val totalBits = words.size * BITS_PER_WORD
        val entBits = totalBits * 32 / 33
        val csBits = totalBits - entBits
        val bits = BooleanArray(totalBits)
        var pos = 0
        for (idx in indices) {
            for (b in (BITS_PER_WORD - 1) downTo 0) {
                bits[pos++] = ((idx ushr b) and 1) == 1
            }
        }
        val entropy = bitsToBytes(bits, entBits)
        val hash = sha256(entropy)
        for (i in 0 until csBits) {
            val bitPos = entBits + i
            val expected = ((hash[i / 8].toInt() ushr (7 - (i % 8))) and 1) == 1
            if (bits[bitPos] != expected) {
                throw VaultCryptoException("Recovery ključ nije ispravan (kontrolni zbir se ne poklapa).")
            }
        }
        return entropy
    }

    /** True when the mnemonic has a valid checksum and only known words. */
    fun isValid(mnemonic: String): Boolean = runCatching { toEntropy(mnemonic) }.isSuccess

    /** The standard BIP39 seed (PBKDF2-HMAC-SHA512, salt "mnemonic"+passphrase). */
    fun seed(mnemonic: String, passphrase: String = ""): ByteArray {
        val salt = ("mnemonic" + passphrase).toByteArray(StandardCharsets.US_ASCII)
        val password = mnemonic.toCharArray()
        val spec = PBEKeySpec(password, salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** The 256-bit wrapping key derived from the mnemonic (first 32 bytes of the BIP39 seed). */
    fun wrappingKey(mnemonic: String): ByteArray = seed(mnemonic).copyOfRange(0, 32)

    // ------------------------------------------------------------- internals

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun readBits(entropy: ByteArray, hash: ByteArray, bitOffset: Int, count: Int): Int {
        var value = 0
        for (i in 0 until count) {
            val bitPos = bitOffset + i
            val bit = if (bitPos < entropy.size * 8) {
                (entropy[bitPos / 8].toInt() ushr (7 - (bitPos % 8))) and 1
            } else {
                val hashPos = bitPos - entropy.size * 8
                (hash[hashPos / 8].toInt() ushr (7 - (hashPos % 8))) and 1
            }
            value = (value shl 1) or bit
        }
        return value
    }

    private fun bitsToBytes(bits: BooleanArray, bitCount: Int): ByteArray {
        val out = ByteArray((bitCount + 7) / 8)
        for (i in 0 until bitCount) {
            if (bits[i]) out[i / 8] = (out[i / 8].toInt() or (1 shl (7 - (i % 8)))).toByte()
        }
        return out
    }
}
