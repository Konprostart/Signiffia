package dev.signiffia.core

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import com.android.apksig.apk.ApkFormatException
import com.android.apksig.apk.ApkSigningBlockNotFoundException
import java.io.File
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.jar.JarFile

/** Raised for user-facing signing / verification failures. */
class SigningException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Which APK Signature Schemes should be written. */
data class SchemeOptions(
    val v1: Boolean = true,
    val v2: Boolean = true,
    val v3: Boolean = true,
    val v4: Boolean = false,
)

/** A private key together with its certificate chain, ready for apksig. */
data class SigningIdentity(
    val alias: String,
    val privateKey: PrivateKey,
    val certificates: List<X509Certificate>,
) {
    val certificate: X509Certificate get() = certificates.first()
}

data class SignerCertificate(
    val subject: String,
    val issuer: String,
    val sha256: String,
    val sha1: String,
    val serialNumber: String,
    val algorithm: String,
    val keyDescription: String,
    val notBefore: String,
    val notAfter: String,
    val expired: Boolean,
    val selfSigned: Boolean,
) {
    companion object {
        fun from(cert: X509Certificate): SignerCertificate = SignerCertificate(
            subject = CertUtils.subject(cert),
            issuer = CertUtils.issuer(cert),
            sha256 = CertUtils.sha256Fingerprint(cert),
            sha1 = CertUtils.sha1Fingerprint(cert),
            serialNumber = CertUtils.serialNumber(cert),
            algorithm = CertUtils.signatureAlgorithm(cert),
            keyDescription = CertUtils.publicKeyDescription(cert),
            notBefore = CertUtils.notBefore(cert),
            notAfter = CertUtils.notAfter(cert),
            expired = CertUtils.isExpired(cert),
            selfSigned = CertUtils.isSelfSigned(cert),
        )
    }
}

/** Result of verifying a signed APK / AAB. */
data class VerificationReport(
    val verified: Boolean,
    val v1: Boolean,
    val v2: Boolean,
    val v3: Boolean,
    val v31: Boolean,
    val v4: Boolean,
    val signers: List<SignerCertificate>,
    val errors: List<String>,
    val warnings: List<String>,
    val checkedMinSdk: Int,
    val lineageLength: Int,
    val notes: List<String> = emptyList(),
)

/** Result of a signing operation. */
data class SignOutcome(
    val output: File,
    val idsigFile: File?,
    val report: VerificationReport,
    val schemes: SchemeOptions,
    val warnings: List<String>,
)

/**
 * APK signing and verification. All cryptographic work is delegated to the official
 * Android `apksig` library — no custom signature algorithm lives here.
 *
 * Verification always runs on the produced file before the caller may save it, so a
 * broken output can never be silently handed to the user.
 */
object SigningEngine {

    const val DEFAULT_MIN_SDK = 26

    /** Guarantees a scheme combination that apksig accepts. */
    fun normalizeSchemes(options: SchemeOptions): SchemeOptions {
        var v2 = options.v2
        var v4 = options.v4
        if (options.v3 && !v2) v2 = true
        if (v4 && !v2 && !options.v3) v2 = true
        if (!options.v1 && !v2 && !options.v3) {
            throw SigningException("Izaberite najmanje jednu potpisnu šemu.")
        }
        if (v4) v4 = true
        return SchemeOptions(v1 = options.v1, v2 = v2, v3 = options.v3, v4 = v4)
    }

    /**
     * Signs [input] into [output] (a new file, the input is never modified).
     *
     * @param minSdk minimum platform the resulting APK targets; apksig uses it to pick
     *   the correct v1 digest algorithms.
     */
    fun signApk(
        input: File,
        output: File,
        identity: SigningIdentity,
        schemes: SchemeOptions,
        minSdk: Int = DEFAULT_MIN_SDK,
        idsigFile: File? = null,
    ): SignOutcome {
        validateInput(input)
        val normalized = normalizeSchemes(schemes)
        if (output.absolutePath == input.absolutePath) {
            throw SigningException("Izlazni fajl ne sme biti isti kao ulazni APK.")
        }
        output.parentFile?.mkdirs()
        if (output.exists() && !output.delete()) {
            throw SigningException("Nije moguće obrisati postojeći izlazni fajl.")
        }
        val idsig = if (normalized.v4) idsigFile ?: File(output.parentFile, output.name + ".idsig") else null
        idsig?.delete()

        val signerConfig = ApkSigner.SignerConfig.Builder(
            identity.alias, identity.privateKey, identity.certificates
        ).build()

        try {
            val builder = ApkSigner.Builder(listOf(signerConfig))
                .setInputApk(input)
                .setOutputApk(output)
                .setMinSdkVersion(minSdk.coerceAtLeast(1))
                .setV1SigningEnabled(normalized.v1)
                .setV2SigningEnabled(normalized.v2)
                .setV3SigningEnabled(normalized.v3)
                .setV4SigningEnabled(normalized.v4)
                .setOtherSignersSignaturesPreserved(false)
                .setDebuggableApkPermitted(true)
                .setAlignmentPreserved(true)
            if (normalized.v4 && idsig != null) {
                builder.setV4SignatureOutputFile(idsig)
            }
            builder.build().sign()
        } catch (t: Throwable) {
            output.delete()
            idsig?.delete()
            throw SigningException(friendlySigningMessage(t), t)
        }

        val report = try {
            verify(output, minSdk = minSdk, v4Signature = idsig)
        } catch (t: Throwable) {
            output.delete()
            idsig?.delete()
            throw SigningException(
                "APK je potpisan, ali provera potpisa nije uspela: ${t.message}", t
            )
        }
        if (!report.verified) {
            val details = report.errors.joinToString("; ").ifBlank { "nepoznata greška" }
            output.delete()
            idsig?.delete()
            throw SigningException("Potpisivanje nije dalo ispravan potpis ($details).")
        }
        return SignOutcome(output, idsig?.takeIf { it.exists() }, report, normalized, report.warnings)
    }

    /**
     * Signs an Android App Bundle. AAB files use a plain JAR (v1) signature — an APK
     * Signature Block (v2/v3) must never be added, otherwise the bundle is invalid.
     */
    fun signBundle(input: File, output: File, identity: SigningIdentity): SignOutcome {
        validateInput(input)
        if (output.absolutePath == input.absolutePath) {
            throw SigningException("Izlazni fajl ne sme biti isti kao ulazni AAB.")
        }
        output.parentFile?.mkdirs()
        if (output.exists() && !output.delete()) {
            throw SigningException("Nije moguće obrisati postojeći izlazni fajl.")
        }
        val signerConfig = ApkSigner.SignerConfig.Builder(
            identity.alias, identity.privateKey, identity.certificates
        ).build()
        try {
            ApkSigner.Builder(listOf(signerConfig))
                .setInputApk(input)
                .setOutputApk(output)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(false)
                .setV3SigningEnabled(false)
                .setV4SigningEnabled(false)
                .setMinSdkVersion(DEFAULT_MIN_SDK)
                .build()
                .sign()
        } catch (t: Throwable) {
            output.delete()
            throw SigningException(friendlySigningMessage(t), t)
        }
        val jarReport = verifyJar(output)
        if (!jarReport.verified) {
            output.delete()
            throw SigningException(
                "AAB je potpisan, ali JAR provera nije uspela: ${jarReport.errors.joinToString("; ")}"
            )
        }
        return SignOutcome(
            output = output,
            idsigFile = null,
            report = jarReport,
            schemes = SchemeOptions(v1 = true, v2 = false, v3 = false, v4 = false),
            warnings = jarReport.warnings,
        )
    }

    /**
     * Verifies an APK signature. [minSdk] must be the lowest platform the APK must
     * install on; when it is unknown, [DEFAULT_MIN_SDK] is used.
     */
    fun verify(
        file: File,
        minSdk: Int = DEFAULT_MIN_SDK,
        maxSdk: Int? = null,
        v4Signature: File? = null,
    ): VerificationReport {
        if (!file.exists()) throw SigningException("Fajl za proveru ne postoji.")
        if (file.length() == 0L) throw SigningException("Fajl je prazan.")
        val notes = mutableListOf<String>()
        val checkedMin = minSdk.coerceAtLeast(1)
        val builder = ApkVerifier.Builder(file).setMinCheckedPlatformVersion(checkedMin)
        if (maxSdk != null) builder.setMaxCheckedPlatformVersion(maxSdk)
        if (v4Signature != null && v4Signature.exists()) builder.setV4SignatureFile(v4Signature)

        val result = try {
            builder.build().verify()
        } catch (e: ApkSigningBlockNotFoundException) {
            return VerificationReport(
                verified = false, v1 = false, v2 = false, v3 = false, v31 = false, v4 = false,
                signers = emptyList(),
                errors = listOf("APK nema potpis (nema APK Signing Block ni v1 potpisa)."),
                warnings = emptyList(), checkedMinSdk = checkedMin, lineageLength = 0, notes = notes,
            )
        } catch (e: ApkFormatException) {
            throw SigningException("Fajl nije ispravan APK: ${e.message}", e)
        } catch (e: Throwable) {
            throw SigningException("Provera potpisa nije uspela: ${e.message}", e)
        }

        // apksig skips v1 verification entirely when the checked platform range starts at
        // API 24, so probe with API 18 (the oldest platform that understands SHA-256 v1
        // digests) to report whether a v1 signature is really present and valid.
        var v1Verified = result.isVerifiedUsingV1Scheme
        if (!v1Verified && checkedMin >= 24) {
            v1Verified = probeV1Signature(file, checkedMin)
        }
        val errors = result.allErrors.map { describeIssue(it) }.distinct().toMutableList()
        if (errors.isEmpty() && !result.isVerified) {
            errors += "Potpis nije ispravan (apksig nije prijavio detalje)."
        }
        val warnings = buildList {
            addAll(result.warnings)
            addAll(result.v1SchemeSigners.flatMap { it.warnings })
            addAll(result.v2SchemeSigners.flatMap { it.warnings })
            addAll(result.v3SchemeSigners.flatMap { it.warnings })
        }.map { describeIssue(it) }.distinct()
        if (v1Verified) notes += "v1 (JAR) potpis je ispravan."
        if (!result.isVerifiedUsingV2Scheme && checkedMin >= 24) {
            notes += "APK nema v2 potpis; na Android 7+ to je dozvoljeno ali nije preporučeno."
        }
        val lineage = runCatching { result.signingCertificateLineage }.getOrNull()
        val lineageLength = lineage?.size() ?: 0
        if (lineageLength > 1) {
            notes += "APK sadrži lineage za rotaciju ključa sa $lineageLength ključeva."
        }
        return VerificationReport(
            verified = result.isVerified,
            v1 = v1Verified,
            v2 = result.isVerifiedUsingV2Scheme,
            v3 = result.isVerifiedUsingV3Scheme,
            v31 = result.isVerifiedUsingV31Scheme,
            v4 = result.isVerifiedUsingV4Scheme,
            signers = result.signerCertificates.map { SignerCertificate.from(it) },
            errors = errors,
            warnings = warnings,
            checkedMinSdk = checkedMin,
            lineageLength = lineageLength,
            notes = notes,
        )
    }

    /**
     * Verifies the JAR signature of an AAB (or any signed zip) with the JDK JAR verifier.
     * Every non-META-INF entry must be covered by a valid signature.
     */
    fun verifyJar(file: File): VerificationReport {
        if (!file.exists()) throw SigningException("Fajl za proveru ne postoji.")
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val certificates = LinkedHashMap<String, X509Certificate>()
        var unsignedEntries = 0
        var checkedEntries = 0
        try {
            JarFile(file, true).use { jar ->
                val entries = jar.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory || entry.name.startsWith("META-INF/")) continue
                    checkedEntries++
                    jar.getInputStream(entry).use { input -> input.readBytes() }
                    val certs = entry.certificates
                    if (certs == null || certs.isEmpty()) {
                        unsignedEntries++
                    } else {
                        certs.filterIsInstance<X509Certificate>().forEach {
                            certificates[CertUtils.sha256Fingerprint(it)] = it
                        }
                    }
                }
            }
        } catch (e: SecurityException) {
            errors += "Potpis nije ispravan: ${e.message ?: "provera digesta nije prošla"}"
        } catch (e: Throwable) {
            throw SigningException("Fajl nije ispravan (potpisani) ZIP/AAB: ${e.message}", e)
        }
        if (checkedEntries == 0) {
            errors += "Arhiva ne sadrži nijedan fajl."
        }
        if (unsignedEntries > 0) {
            errors += "$unsignedEntries fajlova u arhivi nije potpisano."
        }
        if (certificates.isEmpty() && errors.isEmpty()) {
            errors += "Arhiva nema JAR potpis."
        }
        if (errors.isEmpty() && unsignedEntries == 0) {
            warnings += "JAR potpis je ispravan i pokriva sve fajlove."
        }
        return VerificationReport(
            verified = errors.isEmpty(),
            v1 = certificates.isNotEmpty(),
            v2 = false,
            v3 = false,
            v31 = false,
            v4 = false,
            signers = certificates.values.map { SignerCertificate.from(it) },
            errors = errors,
            warnings = warnings,
            checkedMinSdk = 0,
            lineageLength = 0,
            notes = listOf("Provereno $checkedEntries fajlova u arhivi."),
        )
    }

    /** Checks a v1 (JAR) signature in isolation, for APKs that newer schemes shadow. */
    private fun probeV1Signature(file: File, requestedMinSdk: Int): Boolean = runCatching {
        val probeMin = if (requestedMinSdk in 1..17) requestedMinSdk else 18
        ApkVerifier.Builder(file)
            .setMinCheckedPlatformVersion(probeMin)
            .build()
            .verify()
            .isVerifiedUsingV1Scheme
    }.getOrDefault(false)

    private fun validateInput(input: File) {
        if (!input.exists() || !input.isFile) throw SigningException("Ulazni fajl ne postoji.")
        if (input.length() < 64) throw SigningException("Ulazni fajl je previše mali da bi bio APK.")
        if (input.length() > MAX_INPUT_BYTES) {
            throw SigningException("Fajl je veći od dozvoljenih ${MAX_INPUT_BYTES / (1024 * 1024)} MB.")
        }
    }

    private const val MAX_INPUT_BYTES = 4L * 1024 * 1024 * 1024

    private fun describeIssue(issue: ApkVerifier.IssueWithParams): String {
        val raw = issue.toString()
        // apksig renders "ERROR: <text>: <params>"; keep that but strip the prefix.
        val cleaned = raw.removePrefix("ERROR: ").removePrefix("WARNING: ").trim()
        return friendlyIssue(issue.issue, cleaned)
    }

    private fun friendlyIssue(code: ApkVerifier.Issue, fallback: String): String = when (code) {
        ApkVerifier.Issue.JAR_SIG_NO_SIGNATURES -> "nema v1 (JAR) potpisa"
        ApkVerifier.Issue.JAR_SIG_NO_SIGNED_ZIP_ENTRIES -> "v1 potpis ne pokriva nijedan fajl"
        ApkVerifier.Issue.JAR_SIG_ZIP_ENTRY_NOT_SIGNED -> "neki fajl u APK-u nije pokriven v1 potpisom: $fallback"
        ApkVerifier.Issue.JAR_SIG_UNPROTECTED_ZIP_ENTRY -> "fajl nije zaštićen potpisom: $fallback"
        ApkVerifier.Issue.JAR_SIG_NO_ZIP_ENTRY_DIGEST_IN_MANIFEST -> "nedostaje digest u MANIFEST.MF: $fallback"
        ApkVerifier.Issue.JAR_SIG_ZIP_ENTRY_DIGEST_DID_NOT_VERIFY ->
            "sadržaj fajla je izmenjen posle potpisivanja: $fallback"
        ApkVerifier.Issue.JAR_SIG_DID_NOT_VERIFY -> "v1 potpis nije ispravan: $fallback"
        ApkVerifier.Issue.V2_SIG_APK_DIGEST_DID_NOT_VERIFY -> "v2 digest se ne poklapa (APK je izmenjen): $fallback"
        ApkVerifier.Issue.V3_SIG_APK_DIGEST_DID_NOT_VERIFY -> "v3 digest se ne poklapa (APK je izmenjen): $fallback"
        ApkVerifier.Issue.V2_SIG_NO_SIGNATURES -> "v2 blok nema potpis"
        ApkVerifier.Issue.V3_SIG_NO_SIGNATURES -> "v3 blok nema potpis"
        ApkVerifier.Issue.V2_SIG_MISSING -> "APK nema v2 potpis"
        ApkVerifier.Issue.MIN_SIG_SCHEME_FOR_TARGET_SDK_NOT_MET ->
            "APK nema potpisnu šemu koju zahteva njegov targetSdk"
        ApkVerifier.Issue.NO_SIG_FOR_TARGET_SANDBOX_VERSION ->
            "APK nema potpis koji odgovara target sandbox verziji"
        ApkVerifier.Issue.UNEXPECTED_EXCEPTION -> "neočekivana greška pri proveri: $fallback"
        ApkVerifier.Issue.MALFORMED_APK -> "APK je oštećen: $fallback"
        else -> fallback
    }

    private fun friendlySigningMessage(t: Throwable): String {
        val message = (t.message ?: "").lowercase()
        return when {
            t is SigningException -> t.message ?: "Potpisivanje nije uspelo."
            message.contains("min sdk") || message.contains("minsdk") ->
                "APK traži Android verziju koju potpisni alat ne podržava."
            message.contains("signature block") || message.contains("malformed") ->
                "APK je oštećen ili nije ispravan Android APK (${t.message})."
            message.contains("no signatures") || message.contains("signer") ->
                "Problem sa potpisnim ključem: ${t.message}"
            else -> "Potpisivanje nije uspelo: ${t.message ?: t.javaClass.simpleName}"
        }
    }
}
