package dev.signiffia.vault

import dev.signiffia.core.BackupEntry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Deterministic binary codec for the plaintext vault payload: a list of [BackupEntry]
 * (the exact same model the `.sgnbak` backup uses) serialised to a byte array.
 *
 * The vault payload is always encrypted (AES-256-GCM under the Master Vault Key) before it
 * leaves the device, so the plaintext passwords inside never travel in the clear. Reusing
 * [BackupEntry] means an encrypted vault and a `.sgnbak` backup are two interchangeable
 * containers for the same signing material, which is what makes migration lossless.
 */
object VaultCodec {

    private val MAGIC = "SGNVLTP".toByteArray(StandardCharsets.US_ASCII)
    private const val FORMAT_VERSION = 1

    // Defensive bounds mirroring BackupManager so a corrupt vault can never exhaust memory.
    private const val MAX_ENTRIES = 500
    private const val MAX_ENTRY_BYTES = 64 * 1024 * 1024 // 64 MiB per keystore
    private const val MAX_TOTAL_BYTES = 512 * 1024 * 1024

    fun encode(entries: List<BackupEntry>): ByteArray {
        if (entries.isEmpty()) throw VaultCryptoException("Vault nema ključeva za šifrovanje.")
        if (entries.size > MAX_ENTRIES) throw VaultCryptoException("Vault ima previše ključeva.")
        val out = ByteArrayOutputStream()
        out.write(MAGIC)
        out.write(FORMAT_VERSION)
        writeInt(out, entries.size)
        for (entry in entries) {
            writeString(out, entry.id)
            writeString(out, entry.displayName)
            writeString(out, entry.storeType)
            writeBytes(out, entry.keystoreBytes)
            writeString(out, String(entry.storePassword))
            writeString(out, entry.primaryAlias)
            writeInt(out, entry.aliases.size)
            entry.aliases.forEach { writeString(out, it) }
            writeInt(out, entry.keyPasswords.size)
            entry.keyPasswords.forEach { (alias, password) ->
                writeString(out, alias)
                writeString(out, String(password))
            }
            writeString(out, entry.fingerprintSha256)
            writeString(out, entry.source)
        }
        val bytes = out.toByteArray()
        if (bytes.size > MAX_TOTAL_BYTES) {
            throw VaultCryptoException("Vault je prevelik (više od ${MAX_TOTAL_BYTES / (1024 * 1024)} MB).")
        }
        return bytes
    }

    fun decode(bytes: ByteArray): List<BackupEntry> {
        if (bytes.size < MAGIC.size + 1 + 4) throw VaultCryptoException("Vault zapis je prekratak.")
        if (bytes.size > MAX_TOTAL_BYTES) throw VaultCryptoException("Vault zapis je prevelik.")
        val input = ByteArrayInputStream(bytes)
        val magic = ByteArray(MAGIC.size)
        if (input.read(magic) != MAGIC.size || !magic.contentEquals(MAGIC)) {
            throw VaultCryptoException("Zapis nije Signiffia vault.")
        }
        val version = input.read()
        if (version != FORMAT_VERSION) {
            throw VaultCryptoException("Nepodržana verzija vault zapisa ($version).")
        }
        val count = readInt(input)
        if (count < 1 || count > MAX_ENTRIES) throw VaultCryptoException("Vault ima neispravan broj ključeva.")
        return (0 until count).map { readEntry(input) }
    }

    private fun readEntry(input: ByteArrayInputStream): BackupEntry {
        val id = readString(input)
        val displayName = readString(input)
        val storeType = readString(input)
        val keystoreBytes = readBytes(input, MAX_ENTRY_BYTES)
        val storePassword = readString(input).toCharArray()
        val primaryAlias = readString(input)
        val aliasCount = readInt(input)
        if (aliasCount < 1 || aliasCount > 256) throw VaultCryptoException("Neispravan broj alijasa.")
        val aliases = (0 until aliasCount).map { readString(input) }
        val passwordCount = readInt(input)
        if (passwordCount < 0 || passwordCount > 256) throw VaultCryptoException("Neispravan broj lozinki.")
        val keyPasswords = (0 until passwordCount).associate {
            readString(input) to readString(input).toCharArray()
        }
        val fingerprint = readString(input)
        val source = readString(input)
        return BackupEntry(
            id = id,
            displayName = displayName,
            storeType = storeType,
            keystoreBytes = keystoreBytes,
            storePassword = storePassword,
            primaryAlias = primaryAlias,
            aliases = aliases,
            keyPasswords = keyPasswords,
            fingerprintSha256 = fingerprint,
            source = source,
        )
    }

    // ------------------------------------------------------------- primitives

    private fun writeInt(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun readInt(input: ByteArrayInputStream): Int {
        val b1 = input.read()
        val b2 = input.read()
        val b3 = input.read()
        val b4 = input.read()
        if (b1 < 0 || b2 < 0 || b3 < 0 || b4 < 0) throw VaultCryptoException("Vault zapis je oštećen.")
        return (b1 shl 24) or (b2 shl 16) or (b3 shl 8) or b4
    }

    private fun writeString(out: ByteArrayOutputStream, value: String) {
        writeBytes(out, value.toByteArray(StandardCharsets.UTF_8))
    }

    private fun readString(input: ByteArrayInputStream): String {
        val bytes = readBytes(input, MAX_ENTRY_BYTES)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun writeBytes(out: ByteArrayOutputStream, bytes: ByteArray) {
        writeInt(out, bytes.size)
        out.write(bytes)
    }

    private fun readBytes(input: ByteArrayInputStream, limit: Int): ByteArray {
        val length = readInt(input)
        if (length < 0 || length > limit) {
            throw VaultCryptoException("Stavka vault-a prelazi dozvoljenu veličinu ($limit bajtova).")
        }
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(bytes, offset, length - offset)
            if (read <= 0) throw VaultCryptoException("Vault zapis je oštećen (nepotpun sadržaj).")
            offset += read
        }
        return bytes
    }
}
