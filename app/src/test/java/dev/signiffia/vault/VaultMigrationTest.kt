package dev.signiffia.vault

import dev.signiffia.Fixtures
import dev.signiffia.core.BackupEntry
import dev.signiffia.core.CertUtils
import dev.signiffia.core.KeystoreTools
import dev.signiffia.core.SchemeOptions
import dev.signiffia.core.SigningEngine
import dev.signiffia.data.KeyRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * End-to-end proof of the recovery story: seal a real signing key into an encrypted vault,
 * lose every device, recover the vault with only the recovery key on a brand-new device, and
 * confirm the restored key has the SAME fingerprint and can still sign a valid APK.
 *
 * This test exercises the exact path a user follows after losing their phone.
 */
class VaultMigrationTest {

    @Test
    fun `recovered vault preserves the original signing identity`() {
        val dir = Fixtures.tempDir("migration")

        // --- Original device: one real keystore with a known fingerprint ---
        val (keystoreFile, identity) = Fixtures.identity(dir, alias = "release", password = "store-pass-1")
        val originalFingerprint = CertUtils.sha256Fingerprint(identity.certificate)
        val entry = BackupEntry(
            id = "orig-1",
            displayName = "Originalni ključ",
            storeType = KeystoreTools.detectType(keystoreFile),
            keystoreBytes = keystoreFile.readBytes(),
            storePassword = "store-pass-1".toCharArray(),
            primaryAlias = identity.alias,
            aliases = listOf(identity.alias),
            keyPasswords = mapOf(identity.alias to "store-pass-1".toCharArray()),
            fingerprintSha256 = originalFingerprint,
            source = "imported",
        )

        // --- Seal the vault + wrap the MVK with an independent recovery key ---
        val mvk = VaultCrypto.generateKey()
        val recoveryMnemonic = RecoveryKey.generate()
        val recoveryKey = RecoveryKey.wrappingKey(recoveryMnemonic)
        val sealed = VaultService.seal(listOf(entry), mvk, deviceId = "phone-1", vaultVersion = 1, epoch = 1)
        val wrappedMvk = VaultService.wrapMvkForRecovery(mvk, recoveryKey)

        // --- "All devices lost": a new device recovers using only the recovery key ---
        val recoveredMvk = VaultService.unwrapMvkFromRecovery(wrappedMvk, recoveryKey)
        val recoveredEntries = VaultService.unseal(sealed, recoveredMvk)
        assertEquals(1, recoveredEntries.size)
        assertEquals(originalFingerprint, recoveredEntries.single().fingerprintSha256)

        // --- Restore into a fresh local repository on the new device ---
        val repository = KeyRepository(File(dir, "fresh-keys"), Fixtures.FakeCipher("new-device"))
        val restored = repository.restore(recoveredEntries)
        assertEquals(1, restored.size)
        assertEquals(originalFingerprint, restored.single().fingerprintSha256)
        assertFalse("restored fingerprint must never be regenerated",
            restored.single().fingerprintSha256.isBlank())

        // --- The restored key must actually sign a valid APK with the same identity ---
        val record = restored.single()
        val signingIdentity = repository.resolveIdentity(record, record.primaryAlias)
        val signed = File(dir, "signed.apk")
        val outcome = SigningEngine.signApk(
            Fixtures.apkFile(dir, "input.apk"),
            signed,
            signingIdentity,
            SchemeOptions(),
            minSdk = 26,
        )
        assertTrue(outcome.report.verified)
        assertEquals(originalFingerprint, outcome.report.signers.single().sha256)
    }

    @Test
    fun `wrong recovery key cannot unlock the vault`() {
        val mvk = VaultCrypto.generateKey()
        val correctRecoveryKey = RecoveryKey.wrappingKey(RecoveryKey.generate())
        val wrappedMvk = VaultService.wrapMvkForRecovery(mvk, correctRecoveryKey)

        // A different recovery key must not unlock the MVK.
        try {
            val wrong = RecoveryKey.wrappingKey(RecoveryKey.generate())
            VaultService.unwrapMvkFromRecovery(wrappedMvk, wrong)
            throw AssertionError("Expected VaultCryptoException")
        } catch (e: VaultCryptoException) {
            // wrong recovery key must be rejected
        }
        // The correct recovery key unlocks it.
        assertTrue(VaultService.unwrapMvkFromRecovery(wrappedMvk, correctRecoveryKey).contentEquals(mvk))
    }
}
