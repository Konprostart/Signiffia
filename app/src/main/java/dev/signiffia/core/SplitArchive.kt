package dev.signiffia.core

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
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
 * with the signed parts, leaving all other members untouched (content and compression
 * method preserved; APK parts are recompressed as a side effect of signing).
 *
 * The implementation is defensive against malicious archives: entry count, total
 * uncompressed size and per-entry size are bounded, entry names are validated and no
 * signed part is ever held in memory — parts are streamed through the work directory.
 */
object SplitArchive {

    private const val MAX_ENTRIES = 10_000
    private const val MAX_TOTAL_UNCOMPRESSED = 6L * 1024 * 1024 * 1024 // 6 GiB
    private const val MAX_ENTRY_BYTES = 4L * 1024 * 1024 * 1024 // 4 GiB per member
    private const val COPY_BUFFER = 256 * 1024

    fun isSplitArchive(file: File): Boolean = runCatching {
        ZipFile(file).use { zip ->
            val apkCount = countApkEntries(zip)
            zip.getEntry("base.apk") != null || apkCount > 1
        }
    }.getOrDefault(false)

    fun apkEntries(file: File): List<String> = runCatching {
        ZipFile(file).use { zip ->
            val names = mutableListOf<String>()
            var count = 0
            val entries = zip.entries()
            while (entries.hasMoreElements() && count < MAX_ENTRIES) {
                val entry = entries.nextElement()
                count++
                if (!entry.isDirectory && isApkEntry(entry.name)) names += entry.name
            }
            names
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
        // APK part name -> signed copy on disk (never kept in RAM).
        val signedFiles = LinkedHashMap<String, File>()
        val partDir = File(workDir, "split-${System.nanoTime()}").apply { mkdirs() }

        try {
            ZipFile(input).use { zip ->
                validateArchive(zip)

                val entries = enumerate(zip)
                val apkNames = entries.filter { !it.isDirectory && isApkEntry(it.name) }.map { it.name }
                if (apkNames.isEmpty()) throw SigningException("Arhiva ne sadrži nijedan APK deo.")

                apkNames.forEachIndexed { index, name ->
                    val extracted = File(partDir, "part-$index.apk")
                    val signedFile = File(partDir, "part-$index-signed.apk")
                    try {
                        copyEntryBounded(zip, zip.getEntry(name), extracted)
                        val outcome = SigningEngine.signApk(
                            input = extracted,
                            output = signedFile,
                            identity = identity,
                            schemes = schemes,
                            minSdk = minSdk,
                        )
                        signedFiles[name] = signedFile
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
                    }
                }

                output.parentFile?.mkdirs()
                if (output.exists() && !output.delete()) {
                    throw SigningException("Nije moguće obrisati postojeći izlazni fajl.")
                }
                try {
                    ZipOutputStream(FileOutputStream(output)).use { sink ->
                        entries.forEach { entry ->
                            val signedFile = if (isApkEntry(entry.name)) signedFiles[entry.name] else null
                            val newEntry = ZipEntry(entry.name)
                            if (signedFile != null) {
                                // The signed APK is a self-contained compressed zip.
                                newEntry.method = ZipEntry.DEFLATED
                                sink.putNextEntry(newEntry)
                                signedFile.inputStream().use { it.copyTo(sink, COPY_BUFFER) }
                            } else if (!entry.isDirectory) {
                                copyPreservingMethod(zip, entry, sink)
                            } else {
                                sink.putNextEntry(newEntry)
                            }
                            sink.closeEntry()
                        }
                    }
                } catch (t: Throwable) {
                    output.delete()
                    throw SigningException("Arhivu nije moguće upisati: ${t.message}", t)
                }
            }
        } finally {
            partDir.deleteRecursively()
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
        val partDir = File(workDir, "verify-${System.nanoTime()}").apply { mkdirs() }
        try {
            ZipFile(file).use { zip ->
                validateArchive(zip)
                val apkEntries = enumerate(zip).filter { !it.isDirectory && isApkEntry(it.name) }
                if (apkEntries.isEmpty()) throw SigningException("Arhiva ne sadrži nijedan APK deo.")
                apkEntries.forEachIndexed { index, entry ->
                    val extracted = File(partDir, "verify-$index.apk")
                    try {
                        copyEntryBounded(zip, entry, extracted)
                        val report = SigningEngine.verify(extracted, minSdk = minSdk)
                        results += entry.name to report
                    } finally {
                        extracted.delete()
                    }
                }
            }
        } finally {
            partDir.deleteRecursively()
        }
        return SplitVerifyOutcome(results)
    }

    // ---------------------------------------------------------------- internals

    private fun isApkEntry(name: String): Boolean =
        name.endsWith(".apk", ignoreCase = true)

    private fun countApkEntries(zip: ZipFile): Int {
        var count = 0
        var seen = 0
        val entries = zip.entries()
        while (entries.hasMoreElements() && seen < MAX_ENTRIES) {
            val entry = entries.nextElement()
            seen++
            if (!entry.isDirectory && isApkEntry(entry.name)) count++
        }
        return count
    }

    private fun enumerate(zip: ZipFile): List<ZipEntry> {
        val list = mutableListOf<ZipEntry>()
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            list += entries.nextElement()
            if (list.size > MAX_ENTRIES) {
                throw SigningException("Arhiva ima previše stavki (više od $MAX_ENTRIES).")
            }
        }
        return list
    }

    /** Rejects archives that would blow up memory or disk (zip bombs, huge member counts). */
    private fun validateArchive(zip: ZipFile) {
        var total = 0L
        var count = 0
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            count++
            if (count > MAX_ENTRIES) {
                throw SigningException("Arhiva ima previše stavki (više od $MAX_ENTRIES).")
            }
            requireSafeEntryName(entry.name)
            val size = entry.size
            if (size < 0) throw SigningException("Arhiva sadrži neispravnu stavku: ${entry.name}")
            if (size > MAX_ENTRY_BYTES) {
                throw SigningException("Stavka \"${entry.name}\" je veća od dozvoljenog limita.")
            }
            total += size
            if (total > MAX_TOTAL_UNCOMPRESSED) {
                throw SigningException("Arhiva raspakovana prelazi dozvoljenu veličinu.")
            }
        }
    }

    private fun requireSafeEntryName(name: String) {
        if (name.isBlank()) throw SigningException("Arhiva sadrži prazno ime stavke.")
        if (name.startsWith("/") || name.contains("\\")) {
            throw SigningException("Arhiva sadrži sumnjivu putanju: $name")
        }
        val segments = name.split('/')
        if (segments.any { it == ".." }) {
            throw SigningException("Arhiva sadrži sumnjivu putanju (..): $name")
        }
    }

    /** Streams a zip member to [target], enforcing the per-entry decompression limit. */
    private fun copyEntryBounded(zip: ZipFile, entry: ZipEntry, target: File) {
        val source = zip.getInputStream(entry)
        FileOutputStream(target).use { sink ->
            copyBounded(source, sink, entry.size)
        }
    }

    private fun copyBounded(source: InputStream, sink: OutputStream, expectedSize: Long) {
        val buffer = ByteArray(COPY_BUFFER)
        var written = 0L
        while (true) {
            val read = source.read(buffer)
            if (read <= 0) break
            written += read
            if (written > MAX_ENTRY_BYTES) {
                throw SigningException("Stavka prelazi dozvoljenu veličinu pri raspakivanju.")
            }
            sink.write(buffer, 0, read)
        }
        if (expectedSize >= 0 && expectedSize != written) {
            throw SigningException("Stavka je oštećena (veličina se ne poklapa).")
        }
    }

    /** Copies a non-APK member, preserving its stored/compressed method and metadata. */
    private fun copyPreservingMethod(zip: ZipFile, entry: ZipEntry, sink: ZipOutputStream) {
        val newEntry = ZipEntry(entry.name)
        newEntry.time = entry.time
        if (entry.method == ZipEntry.STORED) {
            newEntry.method = ZipEntry.STORED
            newEntry.size = entry.size
            newEntry.compressedSize = entry.compressedSize
            newEntry.crc = entry.crc
            sink.putNextEntry(newEntry)
            zip.getInputStream(entry).use { source ->
                copyBounded(source, sink, entry.size)
            }
        } else {
            newEntry.method = ZipEntry.DEFLATED
            sink.putNextEntry(newEntry)
            zip.getInputStream(entry).use { source ->
                copyBounded(source, sink, entry.size)
            }
        }
    }
}
