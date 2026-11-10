package dev.signiffia.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** One keystore inside a backup archive. */
data class BackupEntry(
    val id: String,
    val displayName: String,
    val storeType: String,
    val keystoreBytes: ByteArray,
    val storePassword: CharArray,
    val primaryAlias: String,
    val aliases: List<String>,
    val keyPasswords: Map<String, CharArray>,
    val fingerprintSha256: String,
    val source: String,
)

/** Result of opening (and fully validating) a backup. */
data class BackupSummary(
    val createdAtMillis: Long,
    val appLabel: String,
    val keys: List<BackupEntry>,
)

/**
 * Passphrase encrypted backup container.
 *
 * Layout: `"SGNBK1" | version(1) | salt(16) | iterations(4) | iv(12) | AES-GCM ciphertext`
 *
 * The ciphertext is a ZIP holding the standard keystore files plus a `backup.properties`
 * manifest with the keystore passwords, so a restore needs only the backup passphrase.
 * Nothing is sent anywhere; the file is written where the user points SAF.
 *
 * Passwords are only ever inside the AES-256-GCM encrypted payload. PBKDF2-HMAC-SHA256
 * with a random 16 byte salt is used for key derivation.
 */
object BackupManager {

    private val MAGIC = "SGNBK1".toByteArray(StandardCharsets.US_ASCII)
    private const val FORMAT_VERSION = 1
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val ITERATIONS = 210_000
    private const val MANIFEST = "backup.properties"

    const val MIN_PASSPHRASE_LENGTH = 8

    val isAvailable: Boolean
        get() = runCatching { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256") }.isSuccess

    fun isBackupFile(file: File): Boolean {
        if (!file.exists() || file.length() < MAGIC.size) return false
        val header = ByteArray(MAGIC.size)
        FileInputStream(file).use { if (it.read(header) != MAGIC.size) return false }
        return header.contentEquals(MAGIC)
    }

    /** Writes an encrypted, self-contained backup. */
    fun write(
        file: File,
        passphrase: CharArray,
        entries: List<BackupEntry>,
        verifyAfterWrite: Boolean = true,
    ) {
        if (entries.isEmpty()) throw BackupException("Nema ključeva za backup.")
        if (passphrase.size < MIN_PASSPHRASE_LENGTH) {
            throw BackupException("Backup lozinka mora imati najmanje $MIN_PASSPHRASE_LENGTH karaktera.")
        }
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val plaintext = buildZip(entries)
        val key = deriveKey(passphrase, salt, ITERATIONS)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext)

        file.parentFile?.mkdirs()
        FileOutputStream(file).use { out ->
            out.write(MAGIC)
            out.write(FORMAT_VERSION)
            out.write(salt)
            out.write(intToBytes(ITERATIONS))
            out.write(iv)
            out.write(ciphertext)
            out.fd.sync()
        }
        // Immediately verify the file we just produced with the same passphrase.
        if (verifyAfterWrite) {
            val check = read(file, passphrase, verifyKeys = true)
            if (check.keys.size != entries.size) {
                throw BackupException("Backup nije moguće pročitati nakon snimanja.")
            }
        }
    }

    /**
     * Opens and fully validates a backup.
     *
     * @param verifyKeys when true (always recommended) every keystore is opened with its
     *   stored password and the certificate fingerprint is compared with the manifest.
     */
    fun read(
        file: File,
        passphrase: CharArray,
        verifyKeys: Boolean = true,
        tempDir: File? = null,
    ): BackupSummary {
        if (!file.exists()) throw BackupException("Backup fajl ne postoji.")
        val bytes = file.readBytes()
        if (bytes.size < MAGIC.size + 1 + SALT_BYTES + 4 + IV_BYTES + 16) {
            throw BackupException("Fajl je previše mali da bi bio Signiffia backup.")
        }
        val magic = bytes.copyOfRange(0, MAGIC.size)
        if (!magic.contentEquals(MAGIC)) {
            throw BackupException("Fajl nije Signiffia backup.")
        }
        var offset = MAGIC.size
        val version = bytes[offset].toInt() and 0xFF
        offset += 1
        if (version != FORMAT_VERSION) {
            throw BackupException("Nepodržana verzija backup formata ($version).")
        }
        val salt = bytes.copyOfRange(offset, offset + SALT_BYTES); offset += SALT_BYTES
        val iterations = bytesToInt(bytes, offset); offset += 4
        if (iterations < 10_000 || iterations > 5_000_000) {
            throw BackupException("Backup header je oštećen (neispravan broj iteracija).")
        }
        val iv = bytes.copyOfRange(offset, offset + IV_BYTES); offset += IV_BYTES
        val ciphertext = bytes.copyOfRange(offset, bytes.size)
        val key = deriveKey(passphrase, salt, iterations)
        val plaintext = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(ciphertext)
        } catch (e: AEADBadTagException) {
            throw BackupException("Pogrešna backup lozinka ili je backup oštećen.", e)
        } catch (e: Throwable) {
            throw BackupException("Backup nije moguće dešifrovati: ${e.message}", e)
        }
        val archive = readZip(plaintext)
        val manifest = archive[MANIFEST]?.let { parseManifest(it) }
            ?: throw BackupException("Backup nema manifest.")
        val keys = manifest.keys.map { entry ->
            val data = archive[entry.fileName]
                ?: throw BackupException("Backup ne sadrži fajl ${entry.fileName}.")
            entry.toBackupEntry(data)
        }
        if (keys.isEmpty()) throw BackupException("Backup ne sadrži nijedan ključ.")

        if (verifyKeys) {
            val workDir = tempDir ?: file.parentFile ?: File(".")
            workDir.mkdirs()
            keys.forEach { entry ->
                val temp = File(workDir, "backup-check-" + entry.id + ".keystore")
                try {
                    temp.writeBytes(entry.keystoreBytes)
                    try {
                        KeystoreTools.verifyKeystore(temp, entry.storePassword, entry.fingerprintSha256)
                    } catch (e: KeystoreException) {
                        throw BackupException(
                            "Backup nije ispravan (${entry.displayName}): ${e.message}", e
                        )
                    }
                } finally {
                    temp.delete()
                }
            }
        }
        return BackupSummary(manifest.createdAt, manifest.appLabel, keys)
    }

    // ---------------------------------------------------------------- internals

    private data class ManifestKey(
        val id: String,
        val displayName: String,
        val storeType: String,
        val fileName: String,
        val storePassword: String,
        val primaryAlias: String,
        val aliases: List<String>,
        val keyPasswords: Map<String, String>,
        val fingerprint: String,
        val source: String,
    )

    private data class Manifest(
        val createdAt: Long,
        val appLabel: String,
        val keys: List<ManifestKey>,
    )

    private fun buildZip(entries: List<BackupEntry>): ByteArray {
        val manifest = Properties()
        manifest["format"] = "signiffia-backup"
        manifest["version"] = FORMAT_VERSION.toString()
        manifest["created"] = System.currentTimeMillis().toString()
        manifest["app"] = "Signiffia"
        manifest["count"] = entries.size.toString()
        entries.forEachIndexed { index, entry ->
            val prefix = "key.$index."
            manifest[prefix + "id"] = entry.id
            manifest[prefix + "name"] = entry.displayName
            manifest[prefix + "type"] = entry.storeType
            manifest[prefix + "file"] = "keystores/${entry.id}.keystore"
            manifest[prefix + "fp"] = entry.fingerprintSha256
            manifest[prefix + "source"] = entry.source
            manifest[prefix + "primary"] = b64(entry.primaryAlias)
            manifest[prefix + "storePassword"] = b64(String(entry.storePassword))
            manifest[prefix + "aliases"] = b64(entry.aliases.joinToString("\u0000"))
            entry.aliases.forEachIndexed { aliasIndex, alias ->
                val password = entry.keyPasswords[alias] ?: entry.storePassword
                manifest["$prefix$aliasIndex.password"] = b64(String(password))
            }
        }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            val manifestBytes = ByteArrayOutputStream().also { manifest.store(it, null) }.toByteArray()
            zip.write(manifestBytes)
            zip.closeEntry()
            entries.forEach { entry ->
                zip.putNextEntry(ZipEntry("keystores/${entry.id}.keystore"))
                zip.write(entry.keystoreBytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun readZip(bytes: ByteArray): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (!name.endsWith("/") && (name.startsWith("keystores/") || name == MANIFEST)) {
                        result[name] = zip.readBytes()
                    }
                    entry = zip.nextEntry
                }
            }
        } catch (e: Throwable) {
            throw BackupException("Sadržaj backup-a je oštećen: ${e.message}", e)
        }
        return result
    }

    private fun parseManifest(bytes: ByteArray): Manifest {
        val properties = Properties()
        try {
            properties.load(ByteArrayInputStream(bytes))
        } catch (e: Throwable) {
            throw BackupException("Manifest backup-a je neispravan.", e)
        }
        if (properties.getProperty("format") != "signiffia-backup") {
            throw BackupException("Manifest nije Signiffia backup.")
        }
        val count = properties.getProperty("count")?.toIntOrNull()
            ?: throw BackupException("Manifest nema broj ključeva.")
        if (count < 1 || count > 500) throw BackupException("Manifest ima neispravan broj ključeva.")
        val keys = (0 until count).map { index ->
            val p = "key.$index."
            fun required(name: String): String = properties.getProperty(p + name)
                ?: throw BackupException("Manifest nema polje $name za ključ #$index.")
            val aliases = unb64(required("aliases")).split("\u0000").filter { it.isNotEmpty() }
            ManifestKey(
                id = required("id"),
                displayName = required("name"),
                storeType = required("type"),
                fileName = required("file"),
                storePassword = unb64(required("storePassword")),
                primaryAlias = unb64(required("primary")),
                aliases = aliases,
                keyPasswords = aliases.mapIndexed { aliasIndex, alias ->
                    alias to unb64(properties.getProperty("$p$aliasIndex.password")
                        ?: required("storePassword"))
                }.toMap(),
                fingerprint = required("fp"),
                source = properties.getProperty(p + "source") ?: "backup",
            )
        }
        return Manifest(
            createdAt = properties.getProperty("created")?.toLongOrNull() ?: 0L,
            appLabel = properties.getProperty("app") ?: "Signiffia",
            keys = keys,
        )
    }

    private fun ManifestKey.toBackupEntry(bytes: ByteArray) = BackupEntry(
        id = id,
        displayName = displayName,
        storeType = storeType,
        keystoreBytes = bytes,
        storePassword = storePassword.toCharArray(),
        primaryAlias = primaryAlias,
        aliases = aliases,
        keyPasswords = keyPasswords.mapValues { it.value.toCharArray() },
        fingerprintSha256 = fingerprint,
        source = source,
    )

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val factory = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        } catch (e: Throwable) {
            throw BackupException("Ovaj uređaj ne podržava PBKDF2-HMAC-SHA256.", e)
        }
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        return try {
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun b64(value: String): String =
        Base64.getEncoder().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun unb64(value: String): String {
        val decoded = try {
            Base64.getDecoder().decode(value)
        } catch (e: Throwable) {
            throw BackupException("Manifest sadrži neispravan zapis.", e)
        }
        return String(decoded, StandardCharsets.UTF_8)
    }

    private fun intToBytes(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte()
    )

    private fun bytesToInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
}
