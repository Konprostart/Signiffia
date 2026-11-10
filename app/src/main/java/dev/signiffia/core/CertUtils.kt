package dev.signiffia.core

import java.security.MessageDigest
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey

/**
 * Certificate helper functions shared by the signing and verification screens.
 *
 * Pure JVM code (no Android dependencies) so that it can be exercised by unit tests.
 */
object CertUtils {

    private val DATE_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.US).withZone(ZoneOffset.UTC)

    fun sha256Fingerprint(cert: X509Certificate): String = fingerprint(cert, "SHA-256")

    fun sha1Fingerprint(cert: X509Certificate): String = fingerprint(cert, "SHA-1")

    fun md5Fingerprint(cert: X509Certificate): String = fingerprint(cert, "MD5")

    /** Upper-case, colon separated hex fingerprint, e.g. `AB:CD:...`. */
    fun fingerprint(cert: X509Certificate, algorithm: String): String =
        hex(MessageDigest.getInstance(algorithm).digest(cert.encoded), separator = ":")

    /** Lower-case hex without separators, convenient for copy & paste. */
    fun compactFingerprint(cert: X509Certificate): String =
        hex(MessageDigest.getInstance("SHA-256").digest(cert.encoded), separator = null)

    fun hex(bytes: ByteArray, separator: String? = ":"): String {
        val sb = StringBuilder(bytes.size * 3)
        bytes.forEachIndexed { index, b ->
            if (index > 0 && separator != null) sb.append(separator)
            sb.append(String.format(Locale.US, "%02X", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    fun subject(cert: X509Certificate): String = cert.subjectX500Principal.name

    fun issuer(cert: X509Certificate): String = cert.issuerX500Principal.name

    fun serialNumber(cert: X509Certificate): String =
        cert.serialNumber.toString(16).uppercase(Locale.US)

    fun notBefore(cert: X509Certificate): String = DATE_FORMAT.format(cert.notBefore.toInstant())

    fun notAfter(cert: X509Certificate): String = DATE_FORMAT.format(cert.notAfter.toInstant())

    fun isExpired(cert: X509Certificate, at: Instant = Instant.now()): Boolean =
        at.isAfter(cert.notAfter.toInstant())

    fun isSelfSigned(cert: X509Certificate): Boolean =
        runCatching { cert.verify(cert.publicKey) }.isSuccess

    /** Human readable public key description, e.g. `RSA 2048` or `EC 256 (secp256r1)`. */
    fun publicKeyDescription(cert: X509Certificate): String =
        describePublicKey(cert.publicKey)

    fun describePublicKey(key: PublicKey): String = when (key) {
        is RSAPublicKey -> "RSA ${key.modulus.bitLength()}"
        is ECPublicKey -> {
            val size = key.params.order.bitLength()
            val curve = when (size) {
                256 -> "secp256r1"
                384 -> "secp384r1"
                521 -> "secp521r1"
                else -> "EC"
            }
            "EC $size ($curve)"
        }
        else -> key.algorithm
    }

    fun signatureAlgorithm(cert: X509Certificate): String =
        cert.sigAlgName.ifBlank { cert.sigAlgOID }
}
