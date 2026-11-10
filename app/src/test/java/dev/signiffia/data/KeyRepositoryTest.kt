package dev.signiffia.data

import dev.signiffia.Fixtures
import dev.signiffia.core.BackupManager
import dev.signiffia.core.CertUtils
import dev.signiffia.core.KeystoreException
import dev.signiffia.core.KeystoreTools
import dev.signiffia.core.SchemeOptions
import dev.signiffia.core.SigningEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class KeyRepositoryTest {

    private fun spec(alias: String = "release", password: String = "super-secret-1") =
        KeystoreTools.KeyGenSpec(
            alias = alias,
            password = password.toCharArray(),
            keyAlgorithm = "RSA",
            keySize = 2048,
            validityDays = 365,
            commonName = "Signiffia test",
            organization = "Signiffia",
            countryCode = "RS",
        )

    private fun repository(
        dir: File = Fixtures.tempDir("repo"),
        cipher: SecretCipher = Fixtures.FakeCipher(),
    ) = KeyRepository(dir, cipher) to dir

    @Test
    fun `generated key is stored encrypted and can sign`() {
        val (repo, dir) = repository()
        val record = repo.createGenerated(spec(), "Moj release ključ")

        assertEquals(1, repo.list().size)
        assertEquals("Moj release ključ", record.displayName)
        assertEquals("generated", record.source)
        assertTrue(record.encStorePassword.startsWith("fake-"))
        assertFalse("plain password must never be stored", record.encStorePassword.contains("super-secret"))

        val (store, keys) = repo.passwords(record)
        assertEquals("super-secret-1", String(store))
        assertEquals("super-secret-1", String(keys["release"]!!))

        val identity = repo.resolveIdentity(record)
        val outcome = SigningEngine.signApk(
            Fixtures.apkFile(dir), File(dir, "out.apk"), identity, SchemeOptions(), minSdk = 26
        )
        assertTrue(outcome.report.verified)
        assertEquals(record.fingerprintSha256, outcome.report.signers.first().sha256)
    }

    @Test
    fun `imported PKCS12 and JKS keystores are usable`() {
        val sourceDir = Fixtures.tempDir("import-src")
        val (pkcs12, pkcs12Identity) = Fixtures.identity(sourceDir, alias = "p12key")
        val (jks, jksIdentity) = Fixtures.identity(
            sourceDir, alias = "jkskey", password = "jks-password-1", storeType = KeystoreTools.TYPE_JKS
        )
        val (repo, _) = repository()

        val record1 = repo.importKeystore(
            KeyImportRequest(pkcs12, "Uvezeni p12", Fixtures.PASSWORD.toCharArray())
        )
        val record2 = repo.importKeystore(
            KeyImportRequest(jks, "Uvezeni jks", "jks-password-1".toCharArray())
        )

        assertEquals(2, repo.list().size)
        assertEquals(CertUtils.sha256Fingerprint(pkcs12Identity.certificate), record1.fingerprintSha256)
        assertEquals(CertUtils.sha256Fingerprint(jksIdentity.certificate), record2.fingerprintSha256)
        assertEquals(KeystoreTools.TYPE_JKS, record2.storeType)
        assertNotNull(repo.resolveIdentity(record2))
        assertTrue("the original imported file must not be moved or deleted", pkcs12.exists())
    }

    @Test
    fun `import with a wrong password stores nothing`() {
        val sourceDir = Fixtures.tempDir("import-wrong")
        val (file, _) = Fixtures.identity(sourceDir)
        val (repo, dir) = repository()

        try {
            repo.importKeystore(KeyImportRequest(file, "x", "wrong-password".toCharArray()))
            fail("Expected KeystoreException")
        } catch (e: KeystoreException) {
            assertTrue(e.message!!.isNotBlank())
        }
        assertTrue(repo.list().isEmpty())
        assertEquals(0, File(dir, "stores").listFiles()?.size ?: 0)
    }

    @Test
    fun `delete removes the file and the record`() {
        val (repo, _) = repository()
        val record = repo.createGenerated(spec(), "temp")
        val file = repo.keystoreFile(record)
        assertTrue(file.exists())

        assertTrue(repo.delete(record.id))
        assertFalse(file.exists())
        assertTrue(repo.list().isEmpty())
        assertFalse(repo.delete(record.id))
    }

    @Test
    fun `rename keeps the same key material`() {
        val (repo, _) = repository()
        val record = repo.createGenerated(spec(), "old name")
        val renamed = repo.rename(record.id, "new name")

        assertEquals("new name", renamed.displayName)
        assertEquals(record.fingerprintSha256, repo.get(record.id)!!.fingerprintSha256)
    }

    @Test
    fun `a lost device key locks passwords but never deletes the keystore`() {
        val dir = Fixtures.tempDir("repo-locked")
        val first = KeyRepository(dir, Fixtures.FakeCipher(keyId = "device-a"))
        val record = first.createGenerated(spec(), "locked key")

        // Simulate app restart on a device where the Android Keystore key is gone.
        val second = KeyRepository(dir, Fixtures.FakeCipher(keyId = "device-b"))
        assertNotNull(second.get(record.id))
        try {
            second.resolveIdentity(record)
            fail("Expected VaultLockedException")
        } catch (e: VaultLockedException) {
            assertTrue(e.message!!.contains("ne može se otključati"))
        }
        assertTrue("the keystore file must survive", second.keystoreFile(record).exists())

        // Re-entering the password recovers the key without regenerating anything.
        val fixed = second.setPasswords(
            record.id, "super-secret-1".toCharArray(), mapOf("release" to "super-secret-1".toCharArray())
        )
        assertNotNull(second.resolveIdentity(fixed))
        assertEquals(record.fingerprintSha256, fixed.fingerprintSha256)
    }

    @Test
    fun `backup and restore move keys to a fresh install`() {
        val originalDir = Fixtures.tempDir("repo-original")
        val original = KeyRepository(originalDir, Fixtures.FakeCipher(keyId = "device-a"))
        val record = original.createGenerated(spec(), "backup key")
        val backupFile = File(Fixtures.tempDir("repo-backup-out"), "keys.sgnbak")

        BackupManager.write(
            backupFile,
            "backup-passphrase".toCharArray(),
            original.backupEntries(listOf(record)),
        )

        // Fresh install: new storage directory and new device cipher key.
        val restoredDir = Fixtures.tempDir("repo-restored")
        val fresh = KeyRepository(restoredDir, Fixtures.FakeCipher(keyId = "device-b"))
        val summary = BackupManager.read(backupFile, "backup-passphrase".toCharArray())
        val restored = fresh.restore(summary.keys)

        assertEquals(1, restored.size)
        assertEquals(
            record.fingerprintSha256,
            fresh.resolveIdentity(restored.first()).let {
                CertUtils.sha256Fingerprint(it.certificate)
            }
        )
        val outcome = SigningEngine.signApk(
            Fixtures.apkFile(restoredDir, "after-restore.apk"),
            File(restoredDir, "signed.apk"),
            fresh.resolveIdentity(restored.first()),
            SchemeOptions(),
            minSdk = 26,
        )
        assertTrue(outcome.report.verified)
    }

    @Test
    fun `restoring the same backup twice does not overwrite the first copy`() {
        val dir = Fixtures.tempDir("repo-duplicate")
        val repo = KeyRepository(dir, Fixtures.FakeCipher())
        val record = repo.createGenerated(spec(), "dup")
        val entries = repo.backupEntries(listOf(record))

        repo.restore(entries).forEach { assertNotEquals(record.id, it.id) }
        assertEquals(2, repo.list().size)
        repo.list().forEach { assertNotNull(repo.resolveIdentity(it)) }
    }

    @Test
    fun `export writes a readable keystore file`() {
        val (repo, dir) = repository()
        val record = repo.createGenerated(spec(), "export me")
        val exported = File(dir, "exported.p12")

        exported.outputStream().use { repo.exportKeystore(record, it) }

        val ks = KeystoreTools.load(exported, KeystoreTools.TYPE_PKCS12, "super-secret-1".toCharArray())
        assertEquals("release", KeystoreTools.aliases(ks).first().alias)
    }

    @Test
    fun `repository index survives a reopen`() {
        val dir = Fixtures.tempDir("repo-reopen")
        val cipher = Fixtures.FakeCipher()
        val repo = KeyRepository(dir, cipher)
        val record = repo.createGenerated(spec(), "persisted")

        val reopened = KeyRepository(dir, cipher)
        val loaded = reopened.get(record.id)
        assertNotNull(loaded)
        assertEquals("persisted", loaded!!.displayName)
        assertNotNull(reopened.resolveIdentity(loaded))
        assertNull(reopened.get("does-not-exist"))
    }
}
