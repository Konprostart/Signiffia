package dev.signiffia.core

import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.DERSequence
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyFactory
import java.security.KeyStore
import java.security.KeyStoreSpi
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Provider
import java.security.SecureRandom
import java.security.UnrecoverableKeyException
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration

/**
 * Minimal but standards compliant implementation of the classic Sun **JKS** keystore.
 *
 * Android does not ship a JKS provider and BouncyCastle only ships BKS/BCFKS/PKCS12, so
 * Signiffia provides its own. This lets users import the `release.jks` files that the
 * Android tooling has produced for years, and keeps per-key passwords working.
 *
 * Format (as used by `keytool`):
 * `magic 0xFEEDFEED`, `version`, entries, and a trailing SHA-1 integrity digest that also
 * covers the store password and the literal string `Mighty Aphrodite`.
 */
class JksKeyStoreSpi : KeyStoreSpi() {

    private class KeyEntry(
        val timestamp: Long,
        val protectedKey: ByteArray,
        val chain: Array<Certificate>,
    )

    private class CertEntry(val timestamp: Long, val certificate: Certificate)

    private val entries = LinkedHashMap<String, Any>()
    private var storePassword: CharArray? = null
    private var certificateFactory: CertificateFactory? = null

    override fun engineGetKey(alias: String?, password: CharArray?): Key? {
        val entry = entries[alias] as? KeyEntry
            ?: throw UnrecoverableKeyException("Alijas \"$alias\" nije privatni ključ.")
        val effective = password ?: storePassword
            ?: throw UnrecoverableKeyException("Lozinka ključa nije prosleđena.")
        val keyBytes = KeyProtector.recover(entry.protectedKey, effective)
        return privateKeyFromPkcs8(keyBytes)
    }

    override fun engineGetCertificateChain(alias: String?): Array<Certificate>? =
        (entries[alias] as? KeyEntry)?.chain?.copyOf()

    override fun engineGetCertificate(alias: String?): Certificate? = when (val entry = entries[alias]) {
        is KeyEntry -> entry.chain.firstOrNull()
        is CertEntry -> entry.certificate
        else -> null
    }

    override fun engineGetCreationDate(alias: String?): Date = when (val entry = entries[alias]) {
        is KeyEntry -> Date(entry.timestamp)
        is CertEntry -> Date(entry.timestamp)
        else -> Date(0)
    }

    override fun engineSetKeyEntry(alias: String?, key: Key?, password: CharArray?, chain: Array<out Certificate>?) {
        requireNotNull(alias) { "alias" }
        requireNotNull(key) { "key" }
        requireNotNull(chain) { "chain" }
        if (chain.isEmpty()) throw IllegalArgumentException("Lanac sertifikata je prazan.")
        val secret = password ?: storePassword
            ?: throw IllegalArgumentException("Lozinka ključa je obavezna.")
        val bytes = key.encoded ?: throw IllegalArgumentException("Ključ nema PKCS#8 zapis.")
        entries[alias] = KeyEntry(
            timestamp = System.currentTimeMillis(),
            protectedKey = KeyProtector.protect(bytes, secret),
            chain = chain.map { it as Certificate }.toTypedArray(),
        )
    }

    override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<out Certificate>?) {
        requireNotNull(alias) { "alias" }
        requireNotNull(key) { "key" }
        requireNotNull(chain) { "chain" }
        entries[alias] = KeyEntry(
            timestamp = System.currentTimeMillis(),
            protectedKey = key,
            chain = chain.map { it as Certificate }.toTypedArray(),
        )
    }

    override fun engineSetCertificateEntry(alias: String?, cert: Certificate?) {
        requireNotNull(alias) { "alias" }
        requireNotNull(cert) { "cert" }
        entries[alias] = CertEntry(System.currentTimeMillis(), cert)
    }

    override fun engineDeleteEntry(alias: String?) {
        entries.remove(alias)
    }

    override fun engineAliases(): Enumeration<String> =
        Collections.enumeration(ArrayList(entries.keys))

    override fun engineContainsAlias(alias: String?): Boolean = entries.containsKey(alias)

    override fun engineSize(): Int = entries.size

    override fun engineIsKeyEntry(alias: String?): Boolean = entries[alias] is KeyEntry

    override fun engineIsCertificateEntry(alias: String?): Boolean = entries[alias] is CertEntry

    override fun engineGetCertificateAlias(cert: Certificate?): String? {
        if (cert == null) return null
        entries.forEach { (alias, entry) ->
            val candidate = when (entry) {
                is KeyEntry -> entry.chain.firstOrNull()
                is CertEntry -> entry.certificate
                else -> null
            }
            if (candidate != null && candidate.encoded.contentEquals(cert.encoded)) return alias
        }
        return null
    }

    override fun engineStore(stream: OutputStream?, password: CharArray?) {
        if (stream == null) throw IOException("Izlazni stream je obavezan.")
        val secret = password ?: storePassword
            ?: throw IOException("Lozinka keystore-a je obavezna za JKS.")
        val payload = ByteArrayOutputStream()
        DataOutputStream(payload).use { out ->
            out.writeInt(MAGIC.toInt())
            out.writeInt(VERSION)
            out.writeInt(entries.size)
            entries.forEach { (alias, entry) ->
                when (entry) {
                    is KeyEntry -> {
                        out.writeInt(TAG_KEY_ENTRY)
                        out.writeUTF(alias)
                        out.writeLong(entry.timestamp)
                        out.writeInt(entry.protectedKey.size)
                        out.write(entry.protectedKey)
                        out.writeInt(entry.chain.size)
                        entry.chain.forEach { cert ->
                            out.writeUTF(cert.type)
                            val encoded = cert.encoded
                            out.writeInt(encoded.size)
                            out.write(encoded)
                        }
                    }
                    is CertEntry -> {
                        out.writeInt(TAG_CERT_ENTRY)
                        out.writeUTF(alias)
                        val encoded = entry.certificate.encoded
                        out.writeLong(entry.timestamp)
                        out.writeUTF(entry.certificate.type)
                        out.writeInt(encoded.size)
                        out.write(encoded)
                    }
                }
            }
        }
        val body = payload.toByteArray()
        val digest = integrityDigest(body, secret)
        stream.write(body)
        stream.write(digest)
        stream.flush()
        storePassword = secret
    }

    override fun engineLoad(stream: InputStream?, password: CharArray?) {
        if (stream == null) {
            entries.clear()
            storePassword = password
            return
        }
        val bytes = stream.readBytes()
        if (bytes.size < 12 + 20) throw IOException("Keystore fajl je prekratak.")
        val body = bytes.copyOfRange(0, bytes.size - 20)
        val expected = bytes.copyOfRange(bytes.size - 20, bytes.size)
        if (password == null) throw IOException("Lozinka keystore-a je obavezna.")
        if (!MessageDigest.isEqual(integrityDigest(body, password), expected)) {
            throw IOException("Keystore was tampered with, or password was incorrect")
        }
        val parsed = LinkedHashMap<String, Any>()
        DataInputStream(ByteArrayInputStream(body)).use { input ->
            val magic = input.readInt().toLong() and 0xFFFFFFFFL
            if (magic != MAGIC) throw IOException("Fajl nije JKS keystore.")
            val version = input.readInt()
            if (version != 1 && version != 2) throw IOException("Nepodržana JKS verzija: $version")
            val count = input.readInt()
            if (count < 0) throw IOException("Neispravan broj unosa u JKS keystore-u.")
            repeat(count) {
                when (val tag = input.readInt()) {
                    TAG_KEY_ENTRY -> {
                        val alias = input.readUTF()
                        val timestamp = input.readLong()
                        val keyLength = input.readInt()
                        if (keyLength < 0 || keyLength > 8 * 1024 * 1024) {
                            throw IOException("Neispravan zapis privatnog ključa.")
                        }
                        val protectedKey = ByteArray(keyLength).also { input.readFully(it) }
                        val chainLength = input.readInt()
                        if (chainLength < 0 || chainLength > 64) throw IOException("Neispravan lanac sertifikata.")
                        val chain = (0 until chainLength).map {
                            val type = input.readUTF()
                            val length = input.readInt()
                            if (length < 0 || length > 16 * 1024 * 1024) throw IOException("Neispravan sertifikat.")
                            val encoded = ByteArray(length).also { buf -> input.readFully(buf) }
                            parseCertificate(type, encoded)
                        }.toTypedArray()
                        parsed[alias] = KeyEntry(timestamp, protectedKey, chain)
                    }
                    TAG_CERT_ENTRY -> {
                        val alias = input.readUTF()
                        val timestamp = input.readLong()
                        val type = input.readUTF()
                        val length = input.readInt()
                        if (length < 0 || length > 16 * 1024 * 1024) throw IOException("Neispravan sertifikat.")
                        val encoded = ByteArray(length).also { buf -> input.readFully(buf) }
                        parsed[alias] = CertEntry(timestamp, parseCertificate(type, encoded))
                    }
                    else -> throw IOException("Nepoznat tip unosa u JKS keystore-u: $tag")
                }
            }
        }
        entries.clear()
        entries.putAll(parsed)
        storePassword = password
    }

    private fun parseCertificate(type: String, encoded: ByteArray): Certificate {
        val factory = certificateFactory ?: CertificateFactory.getInstance(
            if (type.equals("X509", ignoreCase = true)) "X.509" else type
        ).also { certificateFactory = it }
        return factory.generateCertificate(ByteArrayInputStream(encoded))
    }

    private fun integrityDigest(body: ByteArray, password: CharArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-1")
        password.forEach { char ->
            digest.update((char.code ushr 8).toByte())
            digest.update(char.code.toByte())
        }
        digest.update(MIGHTY_APHRODITE)
        digest.update(body)
        return digest.digest()
    }

    companion object {
        const val PROVIDER_NAME = "SigniffiaJKS"
        private const val MAGIC = 0xFEEDFEEDL
        private const val VERSION = 2
        private const val TAG_KEY_ENTRY = 1
        private const val TAG_CERT_ENTRY = 2
        private val MIGHTY_APHRODITE = "Mighty Aphrodite".toByteArray(Charsets.UTF_8)

        fun provider(): Provider = SigniffiaJksProvider

        /** Converts the recovered PKCS#8 bytes into a [PrivateKey] for the supported algorithms. */
        fun privateKeyFromPkcs8(encoded: ByteArray): PrivateKey {
            val info = org.bouncycastle.asn1.pkcs.PrivateKeyInfo.getInstance(encoded)
            val oid = info.privateKeyAlgorithm.algorithm.id
            val algorithm = when (oid) {
                "1.2.840.113549.1.1.1" -> "RSA"
                "1.2.840.10045.2.1" -> "EC"
                "1.2.840.10040.4.1" -> "DSA"
                else -> throw UnrecoverableKeyException("Nepodržan algoritam ključa u JKS-u: $oid")
            }
            return KeyFactory.getInstance(algorithm).generatePrivate(PKCS8EncodedKeySpec(encoded))
        }
    }

    private object SigniffiaJksProvider : Provider(PROVIDER_NAME, 1.0, "Signiffia minimal JKS provider") {
        init {
            put("KeyStore.JKS", JksKeyStoreSpi::class.java.name)
        }
    }

    /**
     * Sun's `SunJKS` key protection: SHA-1 based XOR stream plus a SHA-1 check block,
     * wrapped in an `EncryptedPrivateKeyInfo` with the private OID `1.3.6.1.4.1.42.2.17.1.1`.
     */
    private object KeyProtector {
        private const val SALT_LEN = 20
        private const val CHECK_LEN = 20
        private val OID = ASN1ObjectIdentifier("1.3.6.1.4.1.42.2.17.1.1")
        private val random = SecureRandom()

        private fun passwordBytes(password: CharArray): ByteArray {
            val bytes = ByteArray(password.size * 2)
            password.forEachIndexed { index, char ->
                bytes[index * 2] = (char.code ushr 8).toByte()
                bytes[index * 2 + 1] = char.code.toByte()
            }
            return bytes
        }

        fun protect(keyBytes: ByteArray, password: CharArray): ByteArray {
            val passwd = passwordBytes(password)
            val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
            val encrypted = ByteArray(SALT_LEN + keyBytes.size + CHECK_LEN)
            System.arraycopy(salt, 0, encrypted, 0, SALT_LEN)
            val digest = MessageDigest.getInstance("SHA-1")
            var current = salt
            var remaining = keyBytes.size
            var sourceIndex = 0
            var targetIndex = SALT_LEN
            while (remaining > 0) {
                digest.reset()
                digest.update(passwd)
                digest.update(current)
                current = digest.digest()
                val chunk = minOf(20, remaining)
                for (i in 0 until chunk) {
                    encrypted[targetIndex + i] = (keyBytes[sourceIndex + i].toInt() xor current[i].toInt()).toByte()
                }
                sourceIndex += chunk
                targetIndex += chunk
                remaining -= chunk
            }
            digest.reset()
            digest.update(passwd)
            digest.update(keyBytes)
            val check = digest.digest()
            System.arraycopy(check, 0, encrypted, encrypted.size - CHECK_LEN, CHECK_LEN)

            val algorithmIdentifier = DERSequence(OID)
            val epki = DERSequence(
                org.bouncycastle.asn1.ASN1EncodableVector().apply {
                    add(algorithmIdentifier)
                    add(DEROctetString(encrypted))
                }
            )
            return epki.encoded
        }

        fun recover(protectedKey: ByteArray, password: CharArray): ByteArray {
            val encrypted = try {
                val sequence = ASN1Sequence.getInstance(protectedKey)
                ASN1OctetString.getInstance(sequence.getObjectAt(sequence.size() - 1)).octets
            } catch (t: Throwable) {
                throw UnrecoverableKeyException("Zapis privatnog ključa u JKS-u je oštećen.")
            }
            if (encrypted.size < SALT_LEN + CHECK_LEN + 1) {
                throw UnrecoverableKeyException("Zapis privatnog ključa je prekratak.")
            }
            val passwd = passwordBytes(password)
            val plain = ByteArray(encrypted.size - SALT_LEN - CHECK_LEN)
            val salt = encrypted.copyOfRange(0, SALT_LEN)
            val digest = MessageDigest.getInstance("SHA-1")
            var current = salt
            var remaining = plain.size
            var sourceIndex = SALT_LEN
            var targetIndex = 0
            while (remaining > 0) {
                digest.reset()
                digest.update(passwd)
                digest.update(current)
                current = digest.digest()
                val chunk = minOf(20, remaining)
                for (i in 0 until chunk) {
                    plain[targetIndex + i] = (encrypted[sourceIndex + i].toInt() xor current[i].toInt()).toByte()
                }
                sourceIndex += chunk
                targetIndex += chunk
                remaining -= chunk
            }
            digest.reset()
            digest.update(passwd)
            digest.update(plain)
            val check = digest.digest()
            val stored = encrypted.copyOfRange(encrypted.size - CHECK_LEN, encrypted.size)
            if (!MessageDigest.isEqual(check, stored)) {
                throw UnrecoverableKeyException("Pogrešna lozinka ključa ili je keystore oštećen.")
            }
            return plain
        }
    }
}

/** Convenience helpers for JKS keystores. */
object JksSupport {
    fun install() {
        if (java.security.Security.getProvider(JksKeyStoreSpi.PROVIDER_NAME) == null) {
            java.security.Security.addProvider(JksKeyStoreSpi.provider())
        }
    }

    fun newKeyStore(): KeyStore = KeyStore.getInstance("JKS").apply { load(null, null) }

    fun isJks(file: java.io.File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        val header = file.inputStream().use { it.readNBytes(4) }
        val magic = ((header[0].toLong() and 0xFF) shl 24) or
            ((header[1].toLong() and 0xFF) shl 16) or
            ((header[2].toLong() and 0xFF) shl 8) or
            (header[3].toLong() and 0xFF)
        return magic == 0xFEEDFEEDL
    }
}

