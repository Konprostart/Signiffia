package dev.signiffia.core

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** One APK part inside an APKS/XAPK/APKM archive. */
data class SplitPart(
    val name: String,
    val signed: Boolean,
    val certificateSha256: String?,
    val schemes: SchemeOptions?,
    val error: String? = null,
)

data class SplitSignOutcome(
    val output: File,
    val parts: List<SplitPart>,
    val warnings: List<String>,
)

data class SplitVerifyOutcome(
    val parts: List<Pair<String, VerificationReport>>,
) {
    val allVerified: Boolean get() = parts.isNotEmpty() && parts.all { it.second.verified }
    val distinctSigners: Set<String>
        get() = parts.flatMap { it.second.signers.map { signer -> signer.sha256 } }.toSet()
}

/**
 * Handles split APK containers (`.apks`, `.xapk`, `.apkm`): a ZIP with several `.apk`
 * members plus optional metadata/obb files.
 *
 * Every APK part is signed separately with the same identity and the archive is rebuilt
 * with the signed parts, leaving all other members untouched.
 */
object SplitArchive {

    fun isSplitArchive(file: File): Boolean = runCatching {
        ZipFile(file).use { zip ->
            val apkEntries = zip.entries().toList().count {
                !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true)
            }
            zip.getEntry("base.apk") != null || apkEntries > 1
        }
    }.getOrDefault(false)

    fun apkEntries(file: File): List<String> = runCatching {
        ZipFile(file).use { zip ->
            zip.entries().toList()
                .filter { !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true) }
                .map { it.name }
        }
    }.getOrDefault(emptyList())

    fun sign(
        input: File,
        output: File,
        identity: SigningIdentity,
        schemes: SchemeOptions,
        minSdk: Int = SigningEngine.DEFAULT_MIN_SDK,
        workDir: File,
    ): SplitSignOutcome {
        if (!input.exists()) throw SigningException("Arhiva ne postoji.")
        if (input.absolutePath == output.absolutePath) {
            throw SigningException("Izlazni fajl ne sme biti isti kao ulazni.")
        }
        workDir.mkdirs()
        val parts = mutableListOf<SplitPart>()
        val warnings = mutableListOf<String>()
        val signedApks = LinkedHashMap<String, ByteArray>()

        ZipFile(input).use { zip ->
            val entries = zip.entries().toList()
            val apkNames = entries.filter {
                !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true)
            }.map { it.name }
            if (apkNames.isEmpty()) throw SigningException("Arhiva ne sadrži nijedan APK deo.")

            apkNames.forEachIndexed { index, name ->
                val extracted = File(workDir, "part-$index.apk")
                val signedFile = File(workDir, "part-$index-signed.apk")
                try {
                    zip.getInputStream(zip.getEntry(name)).use { source ->
                        FileOutputStream(extracted).use { sink -> source.copyTo(sink, 256 * 1024) }
                    }
                    val outcome = SigningEngine.signApk(
                        input = extracted,
                        output = signedFile,
                        identity = identity,
                        schemes = schemes,
                        minSdk = minSdk,
                    )
                    signedApks[name] = signedFile.readBytes()
                    warnings += outcome.report.warnings
                    parts += SplitPart(
                        name = name,
                        signed = true,
                        certificateSha256 = outcome.report.signers.firstOrNull()?.sha256,
                        schemes = outcome.schemes,
                    )
                } catch (t: Throwable) {
                    parts += SplitPart(
                        name = name,
                        signed = false,
                        certificateSha256 = null,
                        schemes = null,
                        error = t.message ?: "Potpisivanje dela nije uspelo.",
                    )
                    throw SigningException(
                        "Deo \"$name\" nije moguće potpisati: ${t.message}", t
                    )
                } finally {
                    extracted.delete()
                    signedFile.delete()
                }
            }

            output.parentFile?.mkdirs()
            if (output.exists() && !output.delete()) {
                throw SigningException("Nije moguće obrisati postojeći izlazni fajl.")
            }
            try {
                ZipOutputStream(FileOutputStream(output)).use { sink ->
                    entries.forEach { entry ->
                        val signedBytes = signedApks[entry.name]
                        val newEntry = ZipEntry(entry.name)
                        if (signedBytes != null) {
                            newEntry.method = ZipEntry.DEFLATED
                        } else if (!entry.isDirectory) {
                            newEntry.time = entry.time
                        }
                        sink.putNextEntry(newEntry)
                        if (signedBytes != null) {
                            sink.write(signedBytes)
                        } else if (!entry.isDirectory) {
                            zip.getInputStream(entry).use { it.copyTo(sink, 256 * 1024) }
                        }
                        sink.closeEntry()
                    }
                }
            } catch (t: Throwable) {
                output.delete()
                throw SigningException("Arhivu nije moguće upisati: ${t.message}", t)
            }
        }
        return SplitSignOutcome(output, parts, warnings.distinct())
    }

    /** Extracts and verifies every APK part of a split archive. */
    fun verify(
        file: File,
        minSdk: Int = SigningEngine.DEFAULT_MIN_SDK,
        workDir: File,
    ): SplitVerifyOutcome {
        if (!file.exists()) throw SigningException("Arhiva ne postoji.")
        workDir.mkdirs()
        val results = mutableListOf<Pair<String, VerificationReport>>()
        ZipFile(file).use { zip ->
            val apkEntries = zip.entries().toList().filter {
                !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true)
            }
            if (apkEntries.isEmpty()) throw SigningException("Arhiva ne sadrži nijedan APK deo.")
            apkEntries.forEachIndexed { index, entry ->
                val extracted = File(workDir, "verify-$index.apk")
                try {
                    zip.getInputStream(entry).use { source ->
                        FileOutputStream(extracted).use { sink -> source.copyTo(sink, 256 * 1024) }
                    }
                    val report = SigningEngine.verify(extracted, minSdk = minSdk)
                    results += entry.name to report
                } finally {
                    extracted.delete()
                }
            }
        }
        return SplitVerifyOutcome(results)
    }
}
