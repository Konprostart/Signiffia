package dev.signiffia.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VaultCryptoTest {

    @Test
    fun `encrypt and decrypt roundtrip`() {
        val key = VaultCrypto.generateKey()
        val plaintext = "tajni sadržaj vault-a".toByteArray()
        val aad = VaultCrypto.aad("vault", "1", "1")
        val box = VaultCrypto.encrypt(key, plaintext, aad)
        assertArrayEquals(plaintext, VaultCrypto.decrypt(key, box, aad))
    }

    @Test
    fun `encryption uses a fresh nonce each time`() {
        val key = VaultCrypto.generateKey()
        val plaintext = ByteArray(32) { it.toByte() }
        val aad = VaultCrypto.aad("vault", "1", "1")
        val box1 = VaultCrypto.encrypt(key, plaintext, aad)
        val box2 = VaultCrypto.encrypt(key, plaintext, aad)
        assertFalse("nonce mora biti jedinstven", box1.nonce.contentEquals(box2.nonce))
        assertFalse("ciphertext mora biti jedinstven", box1.ciphertext.contentEquals(box2.ciphertext))
    }

    @Test
    fun `tampered ciphertext is rejected`() {
        val key = VaultCrypto.generateKey()
        val aad = VaultCrypto.aad("vault", "1", "1")
        val box = VaultCrypto.encrypt(key, ByteArray(64) { 1 }, aad)
        val tampered = box.copy(ciphertext = box.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() })
        try {
            VaultCrypto.decrypt(key, tampered, aad)
            fail("Expected VaultCryptoException")
        } catch (e: VaultCryptoException) {
            assertTrue(e.message!!.contains("integriteta", ignoreCase = true))
        }
    }

    @Test
    fun `wrong key is rejected`() {
        val aad = VaultCrypto.aad("vault", "1", "1")
        val box = VaultCrypto.encrypt(VaultCrypto.generateKey(), ByteArray(32) { 7 }, aad)
        try {
            VaultCrypto.decrypt(VaultCrypto.generateKey(), box, aad)
            fail("Expected VaultCryptoException")
        } catch (e: VaultCryptoException) {
            // decryption with the wrong key must fail authentication
        }
    }

    @Test
    fun `ciphertext is bound to its AAD`() {
        val key = VaultCrypto.generateKey()
        val plaintext = ByteArray(32) { 9 }
        val box = VaultCrypto.encrypt(key, plaintext, VaultCrypto.aad("vault", "1", "1"))
        // Reusing the box under a different version/epoch (AAD) must fail.
        try {
            VaultCrypto.decrypt(key, box, VaultCrypto.aad("vault", "2", "1"))
            fail("Expected VaultCryptoException")
        } catch (e: VaultCryptoException) {
            // AAD mismatch = authentication failure
        }
    }

    @Test
    fun `key must be exactly 32 bytes`() {
        try {
            VaultCrypto.encrypt(ByteArray(16), ByteArray(8), VaultCrypto.aad("x"))
            fail("Expected VaultCryptoException")
        } catch (e: VaultCryptoException) {
            assertTrue(e.message!!.contains("256", ignoreCase = true))
        }
    }

    @Test
    fun `wrap and unwrap roundtrip with purpose binding`() {
        val kek = VaultCrypto.generateKey()
        val mvk = VaultCrypto.generateKey()
        val box = VaultCrypto.wrapKey(kek, mvk, "recovery-mvk")
        assertArrayEquals(mvk, VaultCrypto.unwrapKey(kek, box, "recovery-mvk"))
        // Wrong purpose must fail.
        try {
            VaultCrypto.unwrapKey(kek, box, "device-mvk")
            fail("Expected VaultCryptoException")
        } catch (e: VaultCryptoException) {
            // purpose binding enforced via AAD
        }
    }

    @Test
    fun `aad length prefixing is unambiguous`() {
        assertFalse(
            "tuple (a,bc) must not collide with (ab,c)",
            VaultCrypto.aad("a", "bc").contentEquals(VaultCrypto.aad("ab", "c"))
        )
    }

    @Test
    fun `constant time equality is correct`() {
        val a = VaultCrypto.generateKey()
        val b = a.copyOf()
        val c = VaultCrypto.generateKey()
        assertTrue(VaultCrypto.constantTimeEquals(a, b))
        assertFalse(VaultCrypto.constantTimeEquals(a, c))
    }

    @Test
    fun `generated keys differ`() {
        val k1 = VaultCrypto.generateKey()
        val k2 = VaultCrypto.generateKey()
        assertNotEquals(VaultCrypto.sha256Hex(k1), VaultCrypto.sha256Hex(k2))
    }
}
