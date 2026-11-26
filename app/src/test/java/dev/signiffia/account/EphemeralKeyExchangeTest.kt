package dev.signiffia.account

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EphemeralKeyExchangeTest {

    @Test
    fun `both sides derive the same shared secret`() {
        val alice = EphemeralKeyExchange.generateKeyPair()
        val bob = EphemeralKeyExchange.generateKeyPair()

        val aliceSecret = EphemeralKeyExchange.sharedSecret(alice.private, bob.public)
        val bobSecret = EphemeralKeyExchange.sharedSecret(bob.private, alice.public)

        assertArrayEquals(aliceSecret, bobSecret)
        assertEquals(32, aliceSecret.size)
    }

    @Test
    fun `different parties derive different secrets`() {
        val alice = EphemeralKeyExchange.generateKeyPair()
        val bob = EphemeralKeyExchange.generateKeyPair()
        val mallory = EphemeralKeyExchange.generateKeyPair()

        val aliceBob = EphemeralKeyExchange.sharedSecret(alice.private, bob.public)
        val aliceMallory = EphemeralKeyExchange.sharedSecret(alice.private, mallory.public)
        assertFalse(aliceBob.contentEquals(aliceMallory))
    }

    @Test
    fun `public key encoding roundtrips`() {
        val keyPair = EphemeralKeyExchange.generateKeyPair()
        val encoded = EphemeralKeyExchange.encodePublicKey(keyPair.public)
        val decoded = EphemeralKeyExchange.decodePublicKey(encoded)
        // A freshly derived shared secret must still match after round-tripping the key.
        val other = EphemeralKeyExchange.generateKeyPair()
        val direct = EphemeralKeyExchange.sharedSecret(keyPair.private, other.public)
        val viaDecoded = EphemeralKeyExchange.sharedSecret(other.private, decoded)
        assertArrayEquals(direct, viaDecoded)
    }

    @Test
    fun `wrapping key derivation is deterministic and 32 bytes`() {
        val secret = ByteArray(32) { it.toByte() }
        val k1 = EphemeralKeyExchange.deriveWrappingKey(secret)
        val k2 = EphemeralKeyExchange.deriveWrappingKey(secret)
        assertArrayEquals(k1, k2)
        assertEquals(32, k1.size)
    }

    @Test
    fun `confirmation code is stable and six digits`() {
        val secret = ByteArray(32) { 1 }
        val nonce = ByteArray(16) { 2 }
        val c1 = EphemeralKeyExchange.confirmationCode(secret, nonce)
        val c2 = EphemeralKeyExchange.confirmationCode(secret, nonce)
        assertEquals(c1, c2)
        assertTrue(c1.matches(Regex("\\d{6}")))
    }

    @Test
    fun `enrollment wrapping key can wrap and unwrap the MVK`() {
        val alice = EphemeralKeyExchange.generateKeyPair()
        val bob = EphemeralKeyExchange.generateKeyPair()
        val aliceSecret = EphemeralKeyExchange.sharedSecret(alice.private, bob.public)
        val bobSecret = EphemeralKeyExchange.sharedSecret(bob.private, alice.public)
        val aliceKey = EphemeralKeyExchange.deriveWrappingKey(aliceSecret)
        val bobKey = EphemeralKeyExchange.deriveWrappingKey(bobSecret)

        val mvk = dev.signiffia.vault.VaultCrypto.generateKey()
        val wrapped = dev.signiffia.vault.VaultCrypto.wrapKey(aliceKey, mvk, "enroll-mvk")
        assertArrayEquals(mvk, dev.signiffia.vault.VaultCrypto.unwrapKey(bobKey, wrapped, "enroll-mvk"))
    }
}
