package dev.signiffia.core

import dev.signiffia.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class KeystoreToolsTest {

    @Test
    fun `generated RSA keystore can be reopened and the key matches the certificate`() {
        val dir = Fixtures.tempDir("gen-rsa")
        val (file, identity) = Fixtures.identity(dir, keyAlgorithm = "RSA", keySize = 3072)

        assertTrue(file.exists())
        assertEquals("testkey", identity.alias)
        assertTrue(identity.certificates.isNotEmpty())
        assertEquals("RSA", identity.certificate.publicKey.algorithm)
        assertTrue(CertUtils.sha256Fingerprint(identity.certificate).matches(Regex("([0-9A-F]{2}:){31}[0-9A-F]{2}")))
        assertTrue(CertUtils.isSelfSigned(identity.certificate))

        val reopened = KeystoreTools.load(file, KeystoreTools.TYPE_PKCS12, Fixtures.PASSWORD.toCharArray())
        val aliases = KeystoreTools.aliases(reopened)
        assertEquals(1, aliases.size)
        assertEquals("testkey", aliases.first().alias)
        assertTrue(aliases.first().hasPrivateKey)
        assertEquals(
            CertUtils.sha256Fingerprint(identity.certificate),
            aliases.first().fingerprintSha256
        )
    }

    @Test
    fun `generated EC keystore works and uses a P-256 curve`() {
        val dir = Fixtures.tempDir("gen-ec")
        val (_, identity) = Fixtures.identity(dir, keyAlgorithm = "EC", keySize = 256)

        assertEquals("EC", identity.certificate.publicKey.algorithm)
        assertTrue(CertUtils.publicKeyDescription(identity.certificate).startsWith("EC 256"))
    }

    @Test
    fun `wrong password gives a clear error and never corrupts the file`() {
        val dir = Fixtures.tempDir("wrong-pw")
        val (file, _) = Fixtures.identity(dir)
        val before = file.readBytes()

        try {
            KeystoreTools.load(file, KeystoreTools.TYPE_PKCS12, "totally-wrong".toCharArray())
            fail("Expected KeystoreException")
        } catch (e: KeystoreException) {
            assertTrue(e.message!!.lowercase().contains("lozinka"))
        }
        assertTrue("keystore file must stay untouched", before.contentEquals(file.readBytes()))
    }

    @Test
    fun `JKS keystores are read and written by the built-in JKS provider`() {
        val dir = Fixtures.tempDir("jks")
        val (file, identity) = Fixtures.identity(
            dir, storeType = KeystoreTools.TYPE_JKS, password = "jks-password-1"
        )
        assertEquals(KeystoreTools.TYPE_JKS, KeystoreTools.detectType(file))

        val ks = KeystoreTools.load(file, KeystoreTools.TYPE_JKS, "jks-password-1".toCharArray())
        val aliases = KeystoreTools.aliases(ks)
        assertEquals(1, aliases.size)
        assertEquals(
            CertUtils.sha256Fingerprint(identity.certificate),
            aliases.first().fingerprintSha256
        )
    }

    @Test
    fun `PKCS12 type is detected from the header`() {
        val dir = Fixtures.tempDir("detect")
        val (file, _) = Fixtures.identity(dir)
        assertEquals(KeystoreTools.TYPE_PKCS12, KeystoreTools.detectType(file))
    }

    @Test
    fun `keystore with a mismatched key is rejected`() {
        val dir = Fixtures.tempDir("mismatch")
        val (fileA, identityA) = Fixtures.identity(dir, alias = "a")
        val (_, identityB) = Fixtures.identity(dir, alias = "b")

        // Store A's key together with B's certificate: that must never be signed with.
        val ks = KeystoreTools.load(fileA, KeystoreTools.TYPE_PKCS12, Fixtures.PASSWORD.toCharArray())
        ks.setKeyEntry(
            "mixed",
            identityA.privateKey,
            Fixtures.PASSWORD.toCharArray(),
            arrayOf(identityB.certificate)
        )
        val mixed = File(dir, "mixed.p12")
        KeystoreTools.store(ks, mixed, Fixtures.PASSWORD.toCharArray())

        val reloaded = KeystoreTools.load(mixed, KeystoreTools.TYPE_PKCS12, Fixtures.PASSWORD.toCharArray())
        try {
            KeystoreTools.privateKey(reloaded, "mixed", Fixtures.PASSWORD.toCharArray())
            fail("Expected the mismatched key to be rejected")
        } catch (e: KeystoreException) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun `generation validates the input`() {
        val dir = Fixtures.tempDir("validate")
        try {
            KeystoreTools.generate(
                File(dir, "short.p12"),
                KeystoreTools.KeyGenSpec("a", "123".toCharArray())
            )
            fail("Expected validation error for short password")
        } catch (e: KeystoreException) {
            assertTrue(e.message!!.contains("najmanje 6"))
        }
        try {
            KeystoreTools.generate(
                File(dir, "bad.p12"),
                KeystoreTools.KeyGenSpec("a", "long-enough".toCharArray(), keySize = 999)
            )
            fail("Expected validation error for unsupported key size")
        } catch (e: KeystoreException) {
            assertTrue(e.message!!.contains("veličina ključa"))
        }
    }

    @Test
    fun `verifyKeystore checks the expected fingerprint`() {
        val dir = Fixtures.tempDir("verify-keystore")
        val (file, identity) = Fixtures.identity(dir)
        val fingerprint = CertUtils.sha256Fingerprint(identity.certificate)

        KeystoreTools.verifyKeystore(file, Fixtures.PASSWORD.toCharArray(), fingerprint)

        try {
            KeystoreTools.verifyKeystore(file, Fixtures.PASSWORD.toCharArray(), "AA:BB")
            fail("Expected fingerprint mismatch")
        } catch (e: KeystoreException) {
            assertTrue(e.message!!.contains("Fingerprint"))
        }
    }

    @Test
    fun `corrupt keystore file is rejected without crashing`() {
        val dir = Fixtures.tempDir("corrupt-keystore")
        val file = File(dir, "broken.p12")
        file.writeBytes(ByteArray(256) { 0x13 })
        try {
            KeystoreTools.load(file, KeystoreTools.TYPE_PKCS12, "x".toCharArray())
            fail("Expected KeystoreException")
        } catch (e: KeystoreException) {
            assertNotNull(e.message)
        }
        assertFalse(file.length() == 0L)
    }
}
