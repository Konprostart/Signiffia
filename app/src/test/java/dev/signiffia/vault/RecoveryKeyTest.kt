package dev.signiffia.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Validates [RecoveryKey] against the official BIP39 test vectors (from the BIP39
 * specification, entropy -> mnemonic -> PBKDF2 seed). This proves the wordlist and the
 * checksum/derivation match the reference implementation and are not hand-rolled.
 */
class RecoveryKeyTest {

    private fun hex(s: String): ByteArray {
        require(s.length % 2 == 0)
        return ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    @Test
    fun `official 128-bit vector encodes and decodes`() {
        val entropy = hex("00000000000000000000000000000000")
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        assertEquals(mnemonic, RecoveryKey.fromEntropy(entropy))
        assertArrayEquals(entropy, RecoveryKey.toEntropy(mnemonic))
    }

    @Test
    fun `official 256-bit vector encodes and decodes`() {
        val entropy = hex("0000000000000000000000000000000000000000000000000000000000000000")
        // 256-bit zero entropy -> 23x "abandon" + "art" (24 words), per the BIP39 spec.
        val mnemonic = List(23) { "abandon" }.joinToString(" ") + " art"
        assertEquals(24, mnemonic.split(" ").size)
        assertEquals(mnemonic, RecoveryKey.fromEntropy(entropy))
        assertArrayEquals(entropy, RecoveryKey.toEntropy(mnemonic))
    }

    @Test
    fun `official 128-bit seed matches reference PBKDF2`() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        // The reference vectors use the passphrase "TREZOR" (as published in BIP39 test suites).
        val expectedSeed = hex(
            "c55257c360c07c72029aebc1b53c05ed0362ada38ead3e3e9efa3708e53495531" +
                "f09a6987599d18264c1e1c92f2cf141630c7a3c4ab7c81b2f001698e7463b04"
        )
        assertArrayEquals(expectedSeed, RecoveryKey.seed(mnemonic, "TREZOR"))
    }

    @Test
    fun `official 256-bit seed matches reference PBKDF2`() {
        val mnemonic = List(23) { "abandon" }.joinToString(" ") + " art"
        val expectedSeed = hex(
            "bda85446c68413707090a52022edd26a1c9462295029f2e60cd7c4f2bbd3097170af7a4d73245" +
                "cafa9c3cca8d561a7c3de6f5d4a10be8ed2a5e608d68f92fcc8"
        )
        assertArrayEquals(expectedSeed, RecoveryKey.seed(mnemonic, "TREZOR"))
    }

    @Test
    fun `another official vector roundtrips`() {
        val entropy = hex("9e885d952ad362caeb4efe34a8e91bd2")
        val mnemonic = "ozone drill grab fiber curtain grace pudding thank cruise elder eight picnic"
        assertEquals(mnemonic, RecoveryKey.fromEntropy(entropy))
        assertArrayEquals(entropy, RecoveryKey.toEntropy(mnemonic))
    }

    @Test
    fun `wrapping key is the first 32 bytes of the seed`() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val seed = RecoveryKey.seed(mnemonic)
        assertEquals(64, seed.size)
        assertArrayEquals(seed.copyOfRange(0, 32), RecoveryKey.wrappingKey(mnemonic))
    }

    @Test
    fun `generated mnemonic is valid with 24 words`() {
        val mnemonic = RecoveryKey.generate()
        assertEquals(24, mnemonic.trim().split(Regex("\\s+")).size)
        assertTrue(RecoveryKey.isValid(mnemonic))
    }

    @Test
    fun `generated mnemonics are unique`() {
        val a = RecoveryKey.generate()
        val b = RecoveryKey.generate()
        assertFalse(a == b)
    }

    @Test
    fun `wrong checksum is rejected`() {
        // Replace the last word so the checksum no longer matches the entropy.
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon"
        assertFalse(RecoveryKey.isValid(mnemonic))
        try {
            RecoveryKey.toEntropy(mnemonic)
            fail("Expected VaultCryptoException")
        } catch (e: VaultCryptoException) {
            assertTrue(e.message!!.contains("kontrolni", ignoreCase = true))
        }
    }

    @Test
    fun `unknown word is rejected`() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon notaword"
        try {
            RecoveryKey.toEntropy(mnemonic)
            fail("Expected VaultCryptoException")
        } catch (e: VaultCryptoException) {
            assertTrue(e.message!!.contains("nepoznatu", ignoreCase = true))
        }
    }

    @Test
    fun `invalid word count is rejected`() {
        try {
            RecoveryKey.toEntropy("abandon abandon abandon")
            fail("Expected VaultCryptoException")
        } catch (e: VaultCryptoException) {
            assertTrue(e.message!!.contains("12", ignoreCase = true))
        }
    }

    @Test
    fun `recovery key wraps and unwraps the master key`() {
        val mnemonic = RecoveryKey.generate()
        val rk = RecoveryKey.wrappingKey(mnemonic)
        val mvk = VaultCrypto.generateKey()
        val wrapped = VaultService.wrapMvkForRecovery(mvk, rk)
        assertArrayEquals(mvk, VaultService.unwrapMvkFromRecovery(wrapped, rk))
        // A different recovery key must not unwrap it.
        val otherRk = RecoveryKey.wrappingKey(RecoveryKey.generate())
        try {
            VaultService.unwrapMvkFromRecovery(wrapped, otherRk)
            fail("Expected VaultCryptoException")
        } catch (e: VaultCryptoException) {
            // wrong recovery key
        }
    }
}
