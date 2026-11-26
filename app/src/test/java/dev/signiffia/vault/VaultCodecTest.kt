package dev.signiffia.vault

import dev.signiffia.Fixtures
import dev.signiffia.core.BackupEntry
import dev.signiffia.core.CertUtils
import dev.signiffia.core.KeystoreTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class VaultCodecTest {

    private fun entry(dir: File, index: Int): BackupEntry {
        val (file, identity) = Fixtures.identity(dir, alias = "alias$index", password = "pass-$index-123")
        return BackupEntry(
            id = "id$index",
            displayName = "Ključ $index",
            storeType = KeystoreTools.detectType(file),
            keystoreBytes = file.readBytes(),
            storePassword = "pass-$index-123".toCharArray(),
            primaryAlias = identity.alias,
            aliases = listOf(identity.alias),
            keyPasswords = mapOf(identity.alias to "pass-$index-123".toCharArray()),
            fingerprintSha256 = CertUtils.sha256Fingerprint(identity.certificate),
            source = "generated",
        )
    }

    @Test
    fun `encode and decode roundtrip preserves entries and fingerprints`() {
        val dir = Fixtures.tempDir("codec")
        val entries = (0 until 3).map { entry(dir, it) }
        val bytes = VaultCodec.encode(entries)
        val decoded = VaultCodec.decode(bytes)
        assertEquals(3, decoded.size)
        entries.zip(decoded).forEach { (original, restored) ->
            assertEquals(original.id, restored.id)
            assertEquals(original.displayName, restored.displayName)
            assertEquals(original.storeType, restored.storeType)
            assertEquals(original.fingerprintSha256, restored.fingerprintSha256)
            assertTrue(original.keystoreBytes.contentEquals(restored.keystoreBytes))
            assertTrue(String(original.storePassword) == String(restored.storePassword))
            assertEquals(original.aliases, restored.aliases)
            assertEquals(
                String(original.keyPasswords.getValue(original.primaryAlias)),
                String(restored.keyPasswords.getValue(restored.primaryAlias)),
            )
        }
    }

    @Test
    fun `multi-alias entry roundtrips`() {
        val dir = Fixtures.tempDir("codec-multi")
        val (file, identity) = Fixtures.identity(dir, alias = "primary", password = "store-pass-1")
        val entry = BackupEntry(
            id = "multi",
            displayName = "Multi",
            storeType = KeystoreTools.detectType(file),
            keystoreBytes = file.readBytes(),
            storePassword = "store-pass-1".toCharArray(),
            primaryAlias = "primary",
            aliases = listOf("primary", "secondary"),
            keyPasswords = mapOf("primary" to "p1".toCharArray(), "secondary" to "p2".toCharArray()),
            fingerprintSha256 = CertUtils.sha256Fingerprint(identity.certificate),
            source = "imported",
        )
        val decoded = VaultCodec.decode(VaultCodec.encode(listOf(entry))).single()
        assertEquals(listOf("primary", "secondary"), decoded.aliases)
        assertEquals("p1", String(decoded.keyPasswords.getValue("primary")))
        assertEquals("p2", String(decoded.keyPasswords.getValue("secondary")))
    }

    @Test
    fun `empty entries are rejected`() {
        try {
            VaultCodec.encode(emptyList())
            fail("Expected VaultCryptoException")
        } catch (e: VaultCryptoException) {
            // empty vault is invalid
        }
    }

    @Test
    fun `corrupt payload is rejected`() {
        val dir = Fixtures.tempDir("codec-corrupt")
        val bytes = VaultCodec.encode(listOf(entry(dir, 0)))
        // Truncate the tail so the last length-prefixed field is incomplete.
        val truncated = bytes.copyOf(bytes.size - 3)
        try {
            VaultCodec.decode(truncated)
            fail("Expected VaultCryptoException")
        } catch (e: VaultCryptoException) {
            // truncated/corrupt payload must be rejected
        }
    }

    @Test
    fun `wrong magic is rejected`() {
        try {
            VaultCodec.decode(ByteArray(32) { 0x41 })
            fail("Expected VaultCryptoException")
        } catch (e: VaultCryptoException) {
            assertTrue(e.message!!.contains("vault", ignoreCase = true))
        }
    }

    @Test
    fun `sealed vault roundtrips through VaultService`() {
        val dir = Fixtures.tempDir("vault-service")
        val entries = (0 until 2).map { entry(dir, it) }
        val mvk = VaultCrypto.generateKey()
        val sealed = VaultService.seal(entries, mvk, deviceId = "dev1", vaultVersion = 1, epoch = 1)
        val restored = VaultService.unseal(sealed, mvk)
        assertEquals(entries.map { it.fingerprintSha256 }, restored.map { it.fingerprintSha256 })
    }

    @Test
    fun `unseal rejects tampered vault object`() {
        val dir = Fixtures.tempDir("vault-tamper")
        val mvk = VaultCrypto.generateKey()
        val sealed = VaultService.seal(listOf(entry(dir, 0)), mvk, "dev1", 1, 1)
        val tampered = sealed.copy(box = sealed.box.copy(ciphertext = sealed.box.ciphertext.copyOf().also {
            it[0] = (it[0].toInt() xor 0xFF).toByte()
        }))
        try {
            VaultService.unseal(tampered, mvk)
            fail("Expected VaultCryptoException")
        } catch (e: VaultCryptoException) {
            // integrity failure
        }
    }

    @Test
    fun `rollback and revocation guard rejects older snapshots`() {
        val known = VaultManifest(vaultVersion = 5, epoch = 2, deviceId = "d", createdAt = 0, recordsSha256 = "")
        // Older version, same epoch -> reject
        assertTrue(!VaultService.canAccept(known, known.copy(vaultVersion = 4)))
        // Same version, older epoch -> reject
        assertTrue(!VaultService.canAccept(known, known.copy(epoch = 1)))
        // Newer both -> accept
        assertTrue(VaultService.canAccept(known, known.copy(vaultVersion = 6, epoch = 3)))
        // Equal -> accept (idempotent re-upload)
        assertTrue(VaultService.canAccept(known, known))
        // No known snapshot -> accept
        assertTrue(VaultService.canAccept(null, known))
    }
}
