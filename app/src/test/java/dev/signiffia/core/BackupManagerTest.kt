package dev.signiffia.core

import dev.signiffia.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class BackupManagerTest {

    private fun entries(dir: File, count: Int = 2): List<BackupEntry> =
        (0 until count).map { index ->
            val (file, identity) = Fixtures.identity(dir, alias = "key$index", password = "pass-$index-123")
            BackupEntry(
                id = "id$index",
                displayName = "Test ključ $index",
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
    fun `backup roundtrip restores usable keystores`() {
        val dir = Fixtures.tempDir("backup")
        val backup = File(Fixtures.tempDir("backup-out"), "signiffia-backup.sgnbak")

        BackupManager.write(backup, "correct horse battery".toCharArray(), entries(dir))
        assertTrue(backup.exists())
        assertTrue(BackupManager.isBackupFile(backup))
        assertFalse("backup must not leak plain passwords",
            String(backup.readBytes(), Charsets.ISO_8859_1).contains("pass-0-123"))

        val summary = BackupManager.read(backup, "correct horse battery".toCharArray())
        assertEquals(2, summary.keys.size)
        assertEquals("Test ključ 1", summary.keys[1].displayName)

        // Every restored keystore must open with its password and be usable.
        summary.keys.forEach { entry ->
            val temp = File(dir, "restored-${entry.id}.p12")
            temp.writeBytes(entry.keystoreBytes)
            val ks = KeystoreTools.load(temp, entry.storeType, entry.storePassword)
            val (key, chain) = KeystoreTools.privateKey(ks, entry.primaryAlias, entry.storePassword)
            SigningEngine.signApk(
                Fixtures.apkFile(dir, "for-${entry.id}.apk"),
                File(dir, "signed-${entry.id}.apk"),
                SigningIdentity(entry.primaryAlias, key, chain),
                SchemeOptions(),
                minSdk = 26,
            )
        }
    }

    @Test
    fun `wrong backup password is rejected`() {
        val dir = Fixtures.tempDir("backup-wrong")
        val backup = File(dir, "b.sgnbak")
        BackupManager.write(backup, "correct horse battery".toCharArray(), entries(dir, 1))

        try {
            BackupManager.read(backup, "wrong passphrase".toCharArray())
            fail("Expected BackupException")
        } catch (e: BackupException) {
            assertTrue(e.message!!.contains("lozinka", ignoreCase = true))
        }
    }

    @Test
    fun `corrupted backup is rejected`() {
        val dir = Fixtures.tempDir("backup-corrupt")
        val backup = File(dir, "b.sgnbak")
        BackupManager.write(backup, "correct horse battery".toCharArray(), entries(dir, 1))
        val bytes = backup.readBytes()
        bytes[bytes.size - 5] = (bytes[bytes.size - 5] + 1).toByte()
        backup.writeBytes(bytes)

        try {
            BackupManager.read(backup, "correct horse battery".toCharArray())
            fail("Expected BackupException")
        } catch (e: BackupException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    @Test
    fun `backup with a fingerprint mismatch is rejected`() {
        val dir = Fixtures.tempDir("backup-fp")
        val backup = File(dir, "b.sgnbak")
        val broken = entries(dir, 1).map { it.copy(fingerprintSha256 = "AA:BB:CC") }
        BackupManager.write(backup, "correct horse battery".toCharArray(), broken, verifyAfterWrite = false)

        try {
            BackupManager.read(backup, "correct horse battery".toCharArray())
            fail("Expected BackupException")
        } catch (e: BackupException) {
            assertTrue(e.message!!.contains("Fingerprint"))
        }
    }

    @Test
    fun `short passphrase is refused`() {
        val dir = Fixtures.tempDir("backup-short")
        try {
            BackupManager.write(File(dir, "b.sgnbak"), "short".toCharArray(), entries(dir, 1))
            fail("Expected BackupException")
        } catch (e: BackupException) {
            assertTrue(e.message!!.contains("najmanje"))
        }
    }

    @Test
    fun `random file is not detected as a backup`() {
        val dir = Fixtures.tempDir("backup-random")
        val file = File(dir, "random.bin")
        file.writeBytes(ByteArray(256) { 0x7F })
        assertFalse(BackupManager.isBackupFile(file))
        try {
            BackupManager.read(file, "whatever123".toCharArray())
            fail("Expected BackupException")
        } catch (e: BackupException) {
            assertTrue(e.message!!.contains("nije Signiffia backup"))
        }
    }
}
