package dev.signiffia.vault

import dev.signiffia.core.BackupEntry
import java.security.MessageDigest

/**
 * Metadata about one encrypted vault snapshot. Never contains plaintext secrets: only a
 * version, the revocation epoch and a SHA-256 of the (encrypted-at-rest) plaintext.
 */
data class VaultManifest(
    /** Monotonic counter; every successful upload increments it. Higher = newer. */
    val vaultVersion: Long,
    /** Revocation epoch; bumped whenever a device is revoked. Higher = newer. */
    val epoch: Long,
    val deviceId: String,
    val createdAt: Long,
    /** SHA-256 (hex) of the encoded plaintext entries, for integrity checking. */
    val recordsSha256: String,
)

/** An encrypted vault snapshot: a manifest plus the AEAD box holding the entries. */
data class VaultObject(
    val manifest: VaultManifest,
    val box: SealedBox,
)

/**
 * High-level vault operations. Pure JVM, fully unit tested.
 *
 * The vault is a single encrypted blob built from the same [BackupEntry] model as the
 * `.sgnbak` backup. The AES-GCM AAD binds the vault version and epoch, so a ciphertext can
 * never be replayed under a different version/epoch (object substitution + rollback defence).
 */
object VaultService {

    private const val AAD_PREFIX = "vault"

    /** Encrypts [entries] under the Master Vault Key, producing a versioned snapshot. */
    fun seal(
        entries: List<BackupEntry>,
        mvk: ByteArray,
        deviceId: String,
        vaultVersion: Long,
        epoch: Long,
        createdAt: Long = System.currentTimeMillis(),
    ): VaultObject {
        require(vaultVersion > 0) { "vaultVersion mora biti pozitivan." }
        require(epoch > 0) { "epoch mora biti pozitivan." }
        val plaintext = VaultCodec.encode(entries)
        val manifest = VaultManifest(
            vaultVersion = vaultVersion,
            epoch = epoch,
            deviceId = deviceId,
            createdAt = createdAt,
            recordsSha256 = VaultCrypto.sha256Hex(plaintext),
        )
        val aad = aadFor(manifest)
        val box = VaultCrypto.encrypt(mvk, plaintext, aad)
        return VaultObject(manifest, box)
    }

    /** Decrypts a snapshot and verifies the integrity of its entries. */
    fun unseal(obj: VaultObject, mvk: ByteArray): List<BackupEntry> {
        val plaintext = VaultCrypto.decrypt(mvk, obj.box, aadFor(obj.manifest))
        val actual = VaultCrypto.sha256Hex(plaintext)
        if (!actual.equals(obj.manifest.recordsSha256, ignoreCase = true)) {
            throw VaultCryptoException("Vault zapis je izmenjen (SHA-256 se ne poklapa).")
        }
        return VaultCodec.decode(plaintext)
    }

    /** Wraps the Master Vault Key with the recovery key (server sees ciphertext only). */
    fun wrapMvkForRecovery(mvk: ByteArray, recoveryKey: ByteArray): SealedBox =
        VaultCrypto.wrapKey(recoveryKey, mvk, "recovery-mvk")

    fun unwrapMvkFromRecovery(box: SealedBox, recoveryKey: ByteArray): ByteArray =
        VaultCrypto.unwrapKey(recoveryKey, box, "recovery-mvk")

    /**
     * Rollback / revocation guard: a snapshot may replace the known one only if both its
     * vault version and its epoch are greater than or equal to what we already have. This
     * stops an attacker (or a stale client) from replaying an older vault or a snapshot from
     * before a device was revoked.
     */
    fun canAccept(known: VaultManifest?, incoming: VaultManifest): Boolean {
        if (known == null) return true
        return incoming.vaultVersion >= known.vaultVersion && incoming.epoch >= known.epoch
    }

    /** The AAD that binds every vault ciphertext to its exact version and epoch. */
    private fun aadFor(manifest: VaultManifest): ByteArray = VaultCrypto.aad(
        AAD_PREFIX,
        manifest.vaultVersion.toString(),
        manifest.epoch.toString(),
    )
}

/** SHA-256 (hex) of arbitrary bytes — used for opaque object digests the server stores. */
fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
