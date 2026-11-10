package dev.signiffia.data

import dev.signiffia.core.BackupEntry
import dev.signiffia.core.KeystoreException
import dev.signiffia.core.KeystoreTools
import dev.signiffia.core.SigningIdentity
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Properties
import java.util.UUID

/** A keystore managed by Signiffia (one file, one or more key aliases). */
data class KeyRecord(
    val id: String,
    val displayName: String,
    val fileName: String,
    val storeType: String,
    val aliases: List<String>,
    val primaryAlias: String,
    val fingerprintSha256: String,
    val source: String,
    val createdAt: Long,
    val encStorePassword: String,
    val encKeyPasswords: Map<String, String>,
) {
    val aliasCount: Int get() = aliases.size
    val isMultiAlias: Boolean get() = aliases.size > 1
}

data class KeyImportRequest(
    val sourceFile: File,
    val displayName: String,
    val storePassword: CharArray,
    val keyPasswords: Map<String, CharArray> = emptyMap(),
)

/** A keystore file that was already copied into the repository. */
data class StoredKeystore(
    val record: KeyRecord,
    val file: File,
)

/**
 * Stores keystores in app private storage and keeps their passwords encrypted with
 * [SecretCipher]. No password is ever written in clear text, logged or sent anywhere.
 *
 * The class has no Android dependency so its behaviour can be unit tested on the JVM.
 */
class KeyRepository(
    private val baseDir: File,
    private val cipher: SecretCipher,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private val storesDir = File(baseDir, "stores")
    private val indexFile = File(baseDir, "index.properties")

    init {
        storesDir.mkdirs()
    }

    fun list(): List<KeyRecord> =
        loadIndex().sortedByDescending { it.createdAt }

    fun get(id: String): KeyRecord? = loadIndex().firstOrNull { it.id == id }

    fun keystoreFile(record: KeyRecord): File = File(storesDir, record.fileName)

    // ------------------------------------------------------------------ create

    /** Generates a new key and registers it. Never overwrites an existing keystore. */
    fun createGenerated(
        spec: KeystoreTools.KeyGenSpec,
        displayName: String = "",
    ): KeyRecord {
        val id = newId()
        val fileName = "$id.${extensionFor(spec.storeType)}"
        val file = File(storesDir, fileName)
        if (file.exists()) throw KeystoreException("Keystore sa ovim ID-om već postoji.")
        try {
            val keyStore = KeystoreTools.generate(file, spec)
            val cert = keyStore.getCertificate(spec.alias) as? java.security.cert.X509Certificate
                ?: throw KeystoreException("Generisani ključ nema sertifikat.")
            val record = KeyRecord(
                id = id,
                displayName = displayName.ifBlank { "Ključ ${spec.alias}" },
                fileName = fileName,
                storeType = spec.storeType,
                aliases = listOf(spec.alias),
                primaryAlias = spec.alias,
                fingerprintSha256 = dev.signiffia.core.CertUtils.sha256Fingerprint(cert),
                source = "generated",
                createdAt = clock(),
                encStorePassword = cipher.encrypt(String(spec.password)),
                encKeyPasswords = mapOf(spec.alias to cipher.encrypt(String(spec.password))),
            )
            save(listOf(record) + loadIndex())
            return record
        } catch (t: Throwable) {
            file.delete()
            throw t
        }
    }

    // ------------------------------------------------------------------ import

    /**
     * Copies and validates an existing JKS/PKCS12 file.
     *
     * The original file is only ever read; the repository keeps its own copy.
     */
    fun importKeystore(request: KeyImportRequest): KeyRecord {
        if (!request.sourceFile.exists()) throw KeystoreException("Izabrani keystore fajl ne postoji.")
        val type = KeystoreTools.detectType(request.sourceFile)
        val keyStore = KeystoreTools.load(request.sourceFile, type, request.storePassword)
        val keyAliases = KeystoreTools.aliases(keyStore).filter { it.hasPrivateKey }
        if (keyAliases.isEmpty()) {
            throw KeystoreException("Keystore ne sadrži privatni ključ (samo sertifikati).")
        }
        // Prove every private key can be used before storing anything.
        val resolved = LinkedHashMap<String, CharArray>()
        for (info in keyAliases) {
            val password = request.keyPasswords[info.alias] ?: request.storePassword
            KeystoreTools.privateKey(keyStore, info.alias, password)
            resolved[info.alias] = password
        }
        val primary = keyAliases.first()
        val id = newId()
        val fileName = "$id.${extensionFor(type)}"
        val target = File(storesDir, fileName)
        try {
            request.sourceFile.inputStream().use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
            KeystoreTools.load(target, type, request.storePassword)
            val cert = primary.certificate
                ?: throw KeystoreException("Ključ \"${primary.alias}\" nema sertifikat.")
            val record = KeyRecord(
                id = id,
                displayName = request.displayName.ifBlank { request.sourceFile.name },
                fileName = fileName,
                storeType = type,
                aliases = keyAliases.map { it.alias },
                primaryAlias = primary.alias,
                fingerprintSha256 = dev.signiffia.core.CertUtils.sha256Fingerprint(cert),
                source = "imported",
                createdAt = clock(),
                encStorePassword = cipher.encrypt(String(request.storePassword)),
                encKeyPasswords = resolved.mapValues { cipher.encrypt(String(it.value)) },
            )
            save(listOf(record) + loadIndex())
            return record
        } catch (t: Throwable) {
            target.delete()
            throw t
        }
    }

    // ------------------------------------------------------------------ delete

    /**
     * Removes a keystore from the app. The UI must confirm with the user first; the
     * signing key itself is never regenerated or silently replaced.
     */
    fun delete(id: String): Boolean {
        val record = get(id) ?: return false
        val file = keystoreFile(record)
        val backup = File(file.parentFile, file.name + ".deleting")
        if (file.exists()) {
            file.copyTo(backup, overwrite = true)
            if (!file.delete()) throw KeystoreException("Fajl ključa nije moguće obrisati.")
        }
        return try {
            save(loadIndex().filterNot { it.id == id })
            backup.delete()
            true
        } catch (t: Throwable) {
            if (backup.exists()) backup.copyTo(file, overwrite = true)
            throw t
        }
    }

    fun rename(id: String, newName: String): KeyRecord {
        val record = get(id) ?: throw KeystoreException("Ključ nije pronađen.")
        val updated = record.copy(displayName = newName.trim().ifBlank { record.displayName })
        save(listOf(updated) + loadIndex().filterNot { it.id == id })
        return updated
    }

    // ------------------------------------------------------------- passwords

    fun passwords(record: KeyRecord): Pair<CharArray, Map<String, CharArray>> {
        if (!cipher.isAvailable) {
            throw VaultLockedException(
                "Skladište lozinki (Android Keystore) nije dostupno na ovom uređaju."
            )
        }
        val store = cipher.decrypt(record.encStorePassword)
            ?: throw VaultLockedException(
                "Lozinka za \"${record.displayName}\" ne može se otključati na ovom uređaju. " +
                    "Unesite je ponovo da biste nastavili."
            )
        val keyPasswords = record.aliases.associateWith { alias ->
            cipher.decrypt(record.encKeyPasswords[alias] ?: record.encStorePassword)
                ?: throw VaultLockedException(
                    "Lozinka ključa \"$alias\" ne može se otključati. Unesite je ponovo."
                )
        }
        return store.toCharArray() to keyPasswords.mapValues { it.value.toCharArray() }
    }

    /** Re-encrypts passwords after the user re-entered them (lost device key, restore). */
    fun setPasswords(
        id: String,
        storePassword: CharArray,
        keyPasswords: Map<String, CharArray>,
    ): KeyRecord {
        val record = get(id) ?: throw KeystoreException("Ključ nije pronađen.")
        val file = keystoreFile(record)
        val keyStore = KeystoreTools.load(file, record.storeType, storePassword)
        val verified = record.aliases.associateWith { alias ->
            val password = keyPasswords[alias] ?: storePassword
            KeystoreTools.privateKey(keyStore, alias, password)
            password
        }
        val updated = record.copy(
            encStorePassword = cipher.encrypt(String(storePassword)),
            encKeyPasswords = verified.mapValues { cipher.encrypt(String(it.value)) },
        )
        save(listOf(updated) + loadIndex().filterNot { it.id == id })
        return updated
    }

    fun resolveIdentity(record: KeyRecord, alias: String = record.primaryAlias): SigningIdentity {
        val (storePassword, keyPasswords) = passwords(record)
        val file = keystoreFile(record)
        if (!file.exists()) throw KeystoreException("Keystore fajl nedostaje sa uređaja.")
        val keyStore = KeystoreTools.load(file, record.storeType, storePassword)
        val keyPassword = keyPasswords[alias] ?: storePassword
        val (key, chain) = KeystoreTools.privateKey(keyStore, alias, keyPassword)
        return SigningIdentity(alias = alias, privateKey = key, certificates = chain)
    }

    // ------------------------------------------------------------------ export

    /** Streams the raw keystore file out; the caller decides where it goes. */
    fun exportKeystore(record: KeyRecord, output: java.io.OutputStream) {
        val file = keystoreFile(record)
        if (!file.exists()) throw KeystoreException("Keystore fajl nedostaje sa uređaja.")
        FileInputStream(file).use { it.copyTo(output) }
        output.flush()
    }

    fun keystoreBytes(record: KeyRecord): ByteArray {
        val file = keystoreFile(record)
        if (!file.exists()) throw KeystoreException("Keystore fajl nedostaje sa uređaja.")
        val out = ByteArrayOutputStream()
        FileInputStream(file).use { it.copyTo(out) }
        return out.toByteArray()
    }

    /** Builds the plaintext entries that [dev.signiffia.core.BackupManager] encrypts. */
    fun backupEntries(records: List<KeyRecord> = list()): List<BackupEntry> =
        records.map { record ->
            val (store, keys) = passwords(record)
            BackupEntry(
                id = record.id,
                displayName = record.displayName,
                storeType = record.storeType,
                keystoreBytes = keystoreBytes(record),
                storePassword = store,
                primaryAlias = record.primaryAlias,
                aliases = record.aliases,
                keyPasswords = keys,
                fingerprintSha256 = record.fingerprintSha256,
                source = record.source,
            )
        }

    /**
     * Restores previously backed up keystores. Every keystore is validated against its
     * recorded fingerprint before it is registered.
     */
    fun restore(entries: List<BackupEntry>): List<KeyRecord> {
        val restored = mutableListOf<KeyRecord>()
        for (entry in entries) {
            val id = if (get(entry.id) != null) newId() else entry.id
            val fileName = "$id.${extensionFor(entry.storeType)}"
            val target = File(storesDir, fileName)
            try {
                target.writeBytes(entry.keystoreBytes)
                KeystoreTools.verifyKeystore(target, entry.storePassword, entry.fingerprintSha256)
                val record = KeyRecord(
                    id = id,
                    displayName = entry.displayName,
                    fileName = fileName,
                    storeType = KeystoreTools.detectType(target),
                    aliases = entry.aliases.ifEmpty { listOf(entry.primaryAlias) },
                    primaryAlias = entry.primaryAlias,
                    fingerprintSha256 = entry.fingerprintSha256,
                    source = "restored",
                    createdAt = clock(),
                    encStorePassword = cipher.encrypt(String(entry.storePassword)),
                    encKeyPasswords = entry.aliases.associateWith { alias ->
                        cipher.encrypt(String(entry.keyPasswords[alias] ?: entry.storePassword))
                    },
                )
                restored += record
            } catch (t: Throwable) {
                target.delete()
                throw if (t is KeystoreException) t else KeystoreException(
                    "Vraćanje ključa \"${entry.displayName}\" nije uspelo: ${t.message}", t
                )
            }
        }
        save(restored + loadIndex())
        return restored
    }

    // --------------------------------------------------------------- internals

    private fun extensionFor(type: String): String =
        if (type.equals(KeystoreTools.TYPE_JKS, ignoreCase = true)) "jks" else "p12"

    private fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

    @Synchronized
    private fun loadIndex(): List<KeyRecord> {
        if (!indexFile.exists()) return emptyList()
        val properties = Properties()
        return try {
            FileInputStream(indexFile).use { properties.load(it) }
            if (properties.getProperty("version") != INDEX_VERSION) return emptyList()
            val count = properties.getProperty("count")?.toIntOrNull() ?: return emptyList()
            (0 until count).mapNotNull { index -> readRecord(properties, index) }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    private fun readRecord(properties: Properties, index: Int): KeyRecord? {
        val p = "k.$index."
        fun value(name: String): String? = properties.getProperty(p + name)
        val id = value("id") ?: return null
        val aliases = unb64(value("aliases") ?: "").split("\u0000").filter { it.isNotEmpty() }
        if (aliases.isEmpty()) return null
        val keyPasswords = aliases.mapIndexedNotNull { aliasIndex, alias ->
            value("keyPw.$aliasIndex")?.let { alias to it }
        }.toMap()
        return KeyRecord(
            id = id,
            displayName = unb64(value("name") ?: "").ifBlank { "Ključ" },
            fileName = value("file") ?: return null,
            storeType = value("type") ?: KeystoreTools.TYPE_PKCS12,
            aliases = aliases,
            primaryAlias = unb64(value("primary") ?: aliases.first()),
            fingerprintSha256 = value("fp") ?: "",
            source = value("source") ?: "imported",
            createdAt = value("created")?.toLongOrNull() ?: 0L,
            encStorePassword = value("storePw") ?: return null,
            encKeyPasswords = keyPasswords,
        )
    }

    @Synchronized
    private fun save(records: List<KeyRecord>) {
        baseDir.mkdirs()
        val properties = Properties()
        properties["version"] = INDEX_VERSION
        properties["count"] = records.size.toString()
        records.forEachIndexed { index, record ->
            val p = "k.$index."
            properties[p + "id"] = record.id
            properties[p + "name"] = b64(record.displayName)
            properties[p + "file"] = record.fileName
            properties[p + "type"] = record.storeType
            properties[p + "source"] = record.source
            properties[p + "created"] = record.createdAt.toString()
            properties[p + "fp"] = record.fingerprintSha256
            properties[p + "primary"] = b64(record.primaryAlias)
            properties[p + "aliases"] = b64(record.aliases.joinToString("\u0000"))
            properties[p + "storePw"] = record.encStorePassword
            record.aliases.forEachIndexed { aliasIndex, alias ->
                record.encKeyPasswords[alias]?.let { properties["${p}keyPw.$aliasIndex"] = it }
            }
        }
        val temp = File(baseDir, "index.tmp")
        FileOutputStream(temp).use { properties.store(it, null) }
        if (!temp.renameTo(indexFile)) {
            temp.copyTo(indexFile, overwrite = true)
            temp.delete()
        }
    }

    private fun b64(value: String): String =
        Base64.getEncoder().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun unb64(value: String): String = runCatching {
        String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8)
    }.getOrDefault("")

    companion object {
        private const val INDEX_VERSION = "1"
    }
}
