package dev.signiffia

import dev.signiffia.core.KeystoreTools
import dev.signiffia.core.SigningIdentity
import dev.signiffia.data.SecretCipher
import java.io.File
import java.io.FileOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Shared fixtures for the JVM unit tests. */
object Fixtures {

    const val PASSWORD = "test-password-123"

    /** Builds a small but structurally valid ZIP shaped like an APK. */
    fun apkFile(dir: File, name: String = "base.apk"): File {
        val file = File(dir, name)
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(ByteArray(512) { (it % 251).toByte() })
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write("dex\n035\u0000".toByteArray() + ByteArray(2048) { (it * 7 % 256).toByte() })
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("resources.arsc"))
            zip.write(ByteArray(1024) { 0x42 })
            zip.closeEntry()

            // Stored (uncompressed) entry used to simulate tampering.
            val stored = ZipEntry("assets/payload.bin")
            val payload = ByteArray(4096) { 0x5A }
            stored.method = ZipEntry.STORED
            stored.size = payload.size.toLong()
            stored.compressedSize = payload.size.toLong()
            stored.crc = java.util.zip.CRC32().apply { update(payload) }.value
            zip.putNextEntry(stored)
            zip.write(payload)
            zip.closeEntry()
        }
        return file
    }

    /** Flips one byte inside the stored entry so the digest no longer matches. */
    fun tamperWithApk(file: File): File {
        val bytes = file.readBytes()
        val needle = ByteArray(64) { 0x5A }
        val offset = indexOf(bytes, needle)
        require(offset >= 0) { "stored payload not found" }
        bytes[offset + 32] = 0x00
        file.writeBytes(bytes)
        return file
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    /** Creates a keystore + signing identity for tests. */
    fun identity(
        dir: File,
        alias: String = "testkey",
        password: String = PASSWORD,
        storeType: String = KeystoreTools.TYPE_PKCS12,
        keyAlgorithm: String = "RSA",
        keySize: Int = 2048,
    ): Pair<File, SigningIdentity> {
        val file = File(dir, "key-${System.nanoTime()}.${if (storeType == "JKS") "jks" else "p12"}")
        val spec = KeystoreTools.KeyGenSpec(
            alias = alias,
            password = password.toCharArray(),
            keyAlgorithm = keyAlgorithm,
            keySize = keySize,
            validityDays = 365,
            commonName = "Signiffia Test",
            organization = "Signiffia",
            countryCode = "RS",
            storeType = storeType,
        )
        val ks = KeystoreTools.generate(file, spec)
        val (key, chain) = KeystoreTools.privateKey(ks, alias, password.toCharArray())
        return file to SigningIdentity(alias, key, chain)
    }

    /**
     * A deterministic, reversible stand-in for the Android Keystore cipher.
     * Tokens are bound to [keyId]; a token from another key id cannot be decrypted,
     * which mirrors a lost device keystore key.
     */
    class FakeCipher(
        private val keyId: String = "key1",
        private val secret: Byte = 0x5A,
    ) : SecretCipher {
        var available = true
        override val isAvailable: Boolean get() = available

        override fun encrypt(plaintext: String): String {
            val bytes = plaintext.toByteArray(Charsets.UTF_8)
            for (i in bytes.indices) bytes[i] = (bytes[i].toInt() xor secret.toInt()).toByte()
            return "fake-$keyId:" + Base64.getEncoder().encodeToString(bytes)
        }

        override fun decrypt(token: String): String? {
            val prefix = "fake-$keyId:"
            if (!token.startsWith(prefix)) return null
            return runCatching {
                val bytes = Base64.getDecoder().decode(token.removePrefix(prefix))
                for (i in bytes.indices) bytes[i] = (bytes[i].toInt() xor secret.toInt()).toByte()
                String(bytes, Charsets.UTF_8)
            }.getOrNull()
        }
    }

    /** Reuses one temporary directory per test without leaking files. */
    fun tempDir(prefix: String): File =
        File(System.getProperty("java.io.tmpdir"), "signiffia-test-${prefix}-${System.nanoTime()}")
            .also { it.mkdirs() }
}
