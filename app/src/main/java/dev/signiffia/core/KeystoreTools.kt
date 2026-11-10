package dev.signiffia.core

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Security
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import java.util.Locale

/** Thrown for expected, user-facing keystore problems. */
class KeystoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A single entry inside a keystore file. */
data class KeyAliasInfo(
    val alias: String,
    val hasPrivateKey: Boolean,
    val algorithm: String,
    val keySize: Int,
    val certificate: X509Certificate?,
    val chainLength: Int,
) {
    val fingerprintSha256: String?
        get() = certificate?.let { CertUtils.sha256Fingerprint(it) }
}

/**
 * Thin, well tested layer over JCA/BouncyCastle keystore handling.
 *
 * Supported formats:
 *  - `PKCS12` (`.p12`, `.pfx`, `.keystore`) – recommended
 *  - `JKS` (Java KeyStore, `.jks`) – read/write through BouncyCastle because the
 *    Android platform does not ship a JKS provider.
 *
 * Nothing here performs I/O outside the files that the caller passes in.
 */
object KeystoreTools {

    const val TYPE_PKCS12 = "PKCS12"
    const val TYPE_JKS = "JKS"

    private const val JKS_MAGIC = 0xFEEDFEEDL
    private const val BC = BouncyCastleProvider.PROVIDER_NAME

    private val rng = SecureRandom()

    /**
     * The Android platform ships its own, feature-limited `BC` provider. We need the
     * full BouncyCastle (JKS + bcpkix) so the bundled provider replaces the platform one.
     * Safe to call repeatedly and from any thread.
     */
    @Volatile
    private var providersReady = false

    fun ensureProviders() {
        if (providersReady) return
        synchronized(this) {
            if (providersReady) return
            runCatching { JksSupport.install() }
            runCatching {
                val existing = Security.getProvider(BC)
                if (existing != null && existing !is BouncyCastleProvider) {
                    Security.removeProvider(BC)
                }
                if (Security.getProvider(BC) == null) {
                    Security.insertProviderAt(BouncyCastleProvider(), 1)
                }
            }
            providersReady = true
        }
    }

    private fun keySizeOf(key: java.security.PublicKey): Int = when (key) {
        is java.security.interfaces.RSAPublicKey -> key.modulus.bitLength()
        is java.security.interfaces.ECPublicKey -> key.params.order.bitLength()
        else -> 0
    }

    /** Detects the keystore type from the file header. */
    fun detectType(file: File): String {
        val header = ByteArray(4)
        FileInputStream(file).use { input ->
            val read = input.read(header)
            if (read < 4) throw KeystoreException("Fajl je prekratak da bi bio keystore.")
        }
        val magic = ((header[0].toLong() and 0xFF) shl 24) or
            ((header[1].toLong() and 0xFF) shl 16) or
            ((header[2].toLong() and 0xFF) shl 8) or
            (header[3].toLong() and 0xFF)
        return when {
            magic == JKS_MAGIC -> TYPE_JKS
            header[0] == 0x30.toByte() -> TYPE_PKCS12
            else -> TYPE_PKCS12
        }
    }


    /**
     * Opens a keystore with the given password.
     *
     * @throws KeystoreException when the password is wrong or the file is not a keystore.
     */
    fun load(file: File, type: String, password: CharArray): KeyStore {
        ensureProviders()
        if (!file.exists()) throw KeystoreException("Keystore fajl ne postoji.")
        val candidateType = if (type == TYPE_JKS) TYPE_JKS else TYPE_PKCS12
        var last: Throwable? = null
        var first: Throwable? = null
        // Try the platform provider first, then BouncyCastle (more tolerant of odd files).
        for (provider in listOf(null, BC)) {
            try {
                val ks = if (provider == null) {
                    KeyStore.getInstance(candidateType)
                } else {
                    KeyStore.getInstance(candidateType, provider)
                }
                FileInputStream(file).use { ks.load(it, password) }
                return ks
            } catch (t: Throwable) {
                if (first == null) first = t
                last = t
                if (isWrongPassword(t) || t is IOException && t.message?.contains("mac", true) == true) {
                    // Wrong password is provider independent - no point retrying.
                    break
                }
            }
        }
        val error = last ?: first
        throw KeystoreException(describeLoadFailure(error, candidateType), error)
    }

    fun isWrongPassword(t: Throwable?): Boolean {
        var current: Throwable? = t
        while (current != null) {
            val message = (current.message ?: "").lowercase(Locale.US)
            if (current is java.security.UnrecoverableKeyException) return true
            if (message.contains("password") || message.contains("mac invalid") ||
                message.contains("integrity check") || message.contains("decrypt")
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun describeLoadFailure(t: Throwable?, type: String): String {
        val base = if (isWrongPassword(t)) {
            "Pogrešna lozinka keystore-a ili je fajl oštećen."
        } else {
            "Keystore nije moguće otvoriti kao $type format."
        }
        val detail = t?.message?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
        return base + detail
    }

    /** Lists all entries of a keystore without touching private key material. */
    fun aliases(ks: KeyStore): List<KeyAliasInfo> {
        val result = mutableListOf<KeyAliasInfo>()
        val names = ks.aliases().toList().sorted()
        for (alias in names) {
            val cert = runCatching { ks.getCertificate(alias) as? X509Certificate }.getOrNull()
            val chain = runCatching { ks.getCertificateChain(alias) }.getOrNull()
            result += KeyAliasInfo(
                alias = alias,
                hasPrivateKey = runCatching { ks.isKeyEntry(alias) }.getOrDefault(false),
                algorithm = cert?.publicKey?.algorithm ?: "?",
                keySize = cert?.publicKey?.let { keySizeOf(it) } ?: 0,
                certificate = cert,
                chainLength = chain?.size ?: (if (cert != null) 1 else 0),
            )
        }
        return result
    }

    /**
     * Loads the private key + certificate chain for [alias].
     *
     * @param keyPassword password protecting the individual key entry; when it does not
     *   work the store password is tried as well (PKCS12 shares both).
     */
    fun privateKey(
        ks: KeyStore,
        alias: String,
        keyPassword: CharArray,
    ): Pair<PrivateKey, List<X509Certificate>> {
        var last: Throwable? = null
        for (password in listOf(keyPassword)) {
            try {
                val key = ks.getKey(alias, password) as? PrivateKey
                    ?: throw KeystoreException("Alijas \"$alias\" ne sadrži privatni ključ.")
                val chain = ks.getCertificateChain(alias)
                    ?.mapNotNull { it as? X509Certificate }
                    .orEmpty()
                val certs = if (chain.isEmpty()) {
                    (ks.getCertificate(alias) as? X509Certificate)?.let { listOf(it) }
                        ?: throw KeystoreException("Alijas \"$alias\" nema sertifikat.")
                } else {
                    chain
                }
                verifyKeyMatchesCertificate(key, certs.first())
                return key to certs
            } catch (t: Throwable) {
                last = t
            }
        }
        if (isWrongPassword(last)) {
            throw KeystoreException("Pogrešna lozinka za ključ \"$alias\".", last)
        }
        throw KeystoreException(
            "Ključ \"$alias\" nije moguće učitati: ${last?.message ?: "nepoznata greška"}", last
        )
    }

    /** Guards against a mismatched key/certificate pair, which would produce unusable APKs. */
    private fun verifyKeyMatchesCertificate(key: PrivateKey, cert: X509Certificate) {
        val algorithm = when (key.algorithm.uppercase(Locale.US)) {
            "RSA" -> "SHA256withRSA"
            "EC", "ECDSA" -> "SHA256withECDSA"
            "DSA" -> "SHA256withDSA"
            else -> return
        }
        val probe = ByteArray(32).also { rng.nextBytes(it) }
        val signature = Signature.getInstance(algorithm)
        signature.initSign(key)
        signature.update(probe)
        val signed = signature.sign()
        val verifier = Signature.getInstance(algorithm)
        verifier.initVerify(cert.publicKey)
        verifier.update(probe)
        if (!verifier.verify(signed)) {
            throw KeystoreException(
                "Privatni ključ ne odgovara sertifikatu u keystore-u - potpis bi bio neispravan."
            )
        }
    }

    /** Key generation request, filled in by the "create key" screen. */
    data class KeyGenSpec(
        val alias: String,
        val password: CharArray,
        val keyAlgorithm: String = "RSA",
        val keySize: Int = 2048,
        val validityDays: Int = 10950,
        val commonName: String = "Signiffia",
        val organization: String = "",
        val organizationalUnit: String = "",
        val city: String = "",
        val state: String = "",
        val countryCode: String = "",
        val storeType: String = TYPE_PKCS12,
    )

    /**
     * Generates a new self-signed signing identity and writes it to [file].
     * The keystore is always written with the supplied store password.
     */
    fun generate(file: File, spec: KeyGenSpec): KeyStore {
        ensureProviders()
        validateGenerationSpec(spec)
        val keyPair = generateKeyPair(spec.keyAlgorithm, spec.keySize)
        val certificate = createSelfSignedCertificate(keyPair, spec)
        val keyStore = KeyStore.getInstance(spec.storeType)
        keyStore.load(null, spec.password)
        keyStore.setKeyEntry(spec.alias, keyPair.private, spec.password, arrayOf(certificate))
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { keyStore.store(it, spec.password) }
        // Immediately prove that what we wrote can be read back with the same password.
        val reloaded = load(file, spec.storeType, spec.password)
        val reloadedCert = reloaded.getCertificate(spec.alias) as? X509Certificate
            ?: throw KeystoreException("Novi keystore nije moguće pročitati nakon snimanja.")
        if (reloadedCert.encoded.contentEquals(certificate.encoded).not()) {
            throw KeystoreException("Sertifikat u novom keystore-u se ne poklapa sa generisanim.")
        }
        return keyStore
    }

    fun validateGenerationSpec(spec: KeyGenSpec) {
        if (spec.alias.isBlank()) throw KeystoreException("Alijas ne sme biti prazan.")
        if (spec.password.size < 6) {
            throw KeystoreException("Lozinka mora imati najmanje 6 karaktera.")
        }
        val valid = when (spec.keyAlgorithm) {
            "RSA" -> spec.keySize in setOf(2048, 3072, 4096)
            "EC" -> spec.keySize in setOf(256, 384, 521)
            else -> false
        }
        if (!valid) throw KeystoreException("Nepodržana veličina ključa za ${spec.keyAlgorithm}.")
        if (spec.validityDays < 1) throw KeystoreException("Period važenja mora biti najmanje 1 dan.")
    }

    fun generateKeyPair(algorithm: String, keySize: Int): java.security.KeyPair {
        val generator = KeyPairGenerator.getInstance(algorithm)
        if (algorithm == "EC") {
            val curve = when (keySize) {
                256 -> "secp256r1"
                384 -> "secp384r1"
                521 -> "secp521r1"
                else -> throw KeystoreException("Nepodržana EC kriva: $keySize bita.")
            }
            generator.initialize(ECGenParameterSpec(curve), rng)
        } else {
            generator.initialize(keySize, rng)
        }
        return generator.generateKeyPair()
    }

    /** Builds a self-signed X.509 v3 certificate suitable for APK signing. */
    fun createSelfSignedCertificate(
        keyPair: java.security.KeyPair,
        spec: KeyGenSpec,
    ): X509Certificate {
        ensureProviders()
        val subject = X500Name(buildDistinguishedName(spec))
        val now = Date()
        val notBefore = Date(now.time - 24L * 60 * 60 * 1000)
        val notAfter = Date(now.time + spec.validityDays.toLong() * 24 * 60 * 60 * 1000)
        val serial = BigInteger(160, rng).abs().max(BigInteger.ONE)
        val builder = JcaX509v3CertificateBuilder(
            subject, serial, notBefore, notAfter, subject, keyPair.public
        )
        val extensions = JcaX509ExtensionUtils()
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(
            Extension.keyUsage, true,
            KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyCertSign or KeyUsage.cRLSign)
        )
        builder.addExtension(
            Extension.subjectKeyIdentifier, false,
            extensions.createSubjectKeyIdentifier(keyPair.public)
        )
        builder.addExtension(
            Extension.authorityKeyIdentifier, false,
            extensions.createAuthorityKeyIdentifier(keyPair.public)
        )
        val signatureAlgorithm = when (keyPair.private.algorithm.uppercase(Locale.US)) {
            "RSA" -> "SHA256withRSA"
            "EC", "ECDSA" -> "SHA256withECDSA"
            else -> throw KeystoreException("Nepodržan algoritam ključa: ${keyPair.private.algorithm}")
        }
        // Use the platform's default JCA providers here: on Android that is Conscrypt, so
        // certificate generation does not depend on which BouncyCastle build is installed.
        val signer = JcaContentSignerBuilder(signatureAlgorithm).build(keyPair.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    private fun buildDistinguishedName(spec: KeyGenSpec): String {
        val parts = mutableListOf<String>()
        parts += "CN=${escape(spec.commonName.ifBlank { "Signiffia" })}"
        spec.organizationalUnit.takeIf { it.isNotBlank() }?.let { parts += "OU=${escape(it)}" }
        spec.organization.takeIf { it.isNotBlank() }?.let { parts += "O=${escape(it)}" }
        spec.city.takeIf { it.isNotBlank() }?.let { parts += "L=${escape(it)}" }
        spec.state.takeIf { it.isNotBlank() }?.let { parts += "ST=${escape(it)}" }
        spec.countryCode.takeIf { it.isNotBlank() }?.let { parts += "C=${escape(it.uppercase(Locale.US))}" }
        return parts.joinToString(",")
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace(",", "\\,").replace("+", "\\+")
            .replace("\"", "\\\"").replace("<", "\\<").replace(">", "\\>")
            .replace(";", "\\;").replace("=", "\\=")

    /** Writes [ks] to [file] with the given password, atomically through a temp file. */
    fun store(ks: KeyStore, file: File, password: CharArray) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        try {
            FileOutputStream(temp).use { ks.store(it, password) }
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    /**
     * Verifies that a keystore file can be opened with [password] and that every
     * private key entry is usable. Used by the backup verification and import screens.
     */
    fun verifyKeystore(file: File, password: CharArray, expectedFingerprint: String? = null): List<KeyAliasInfo> {
        val type = detectType(file)
        val ks = load(file, type, password)
        val entries = aliases(ks).filter { it.hasPrivateKey }
        if (entries.isEmpty()) {
            throw KeystoreException("Keystore ne sadrži nijedan privatni ključ.")
        }
        entries.forEach { privateKey(ks, it.alias, password) }
        if (expectedFingerprint != null) {
            val actual = entries.mapNotNull { it.fingerprintSha256 }
            if (actual.none { it.equals(expectedFingerprint, ignoreCase = true) }) {
                throw KeystoreException(
                    "Fingerprint u keystore-u se ne poklapa sa očekivanim. Backup možda nije ispravan."
                )
            }
        }
        return entries
    }
}
