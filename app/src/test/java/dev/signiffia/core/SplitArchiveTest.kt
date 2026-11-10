package dev.signiffia.core

import dev.signiffia.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class SplitArchiveTest {

    private fun splitArchive(dir: File): File {
        val archive = File(dir, "app.xapk")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("{\"package_name\":\"com.example\"}".toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("icon.png"))
            zip.write(ByteArray(128) { 0x7F })
            zip.closeEntry()

            listOf("base.apk", "config.arm64_v8a.apk", "config.xxhdpi.apk").forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(Fixtures.apkFile(dir, name).readBytes())
                zip.closeEntry()
            }
        }
        return archive
    }

    @Test
    fun `all APK parts are signed with the same key and other files are preserved`() {
        val dir = Fixtures.tempDir("split-sign")
        val archive = splitArchive(dir)
        val before = archive.readBytes()
        val (_, identity) = Fixtures.identity(dir)
        val output = File(dir, "signed.xapk")

        val outcome = SplitArchive.sign(
            input = archive,
            output = output,
            identity = identity,
            schemes = SchemeOptions(v1 = true, v2 = true, v3 = true),
            minSdk = 26,
            workDir = Fixtures.tempDir("split-work"),
        )

        assertEquals(3, outcome.parts.size)
        assertTrue(outcome.parts.all { it.signed })
        assertTrue("input archive must stay untouched", before.contentEquals(archive.readBytes()))

        // Metadata files survive untouched.
        ZipFile(output).use { zip ->
            assertEquals("{\"package_name\":\"com.example\"}", zip.getInputStream(zip.getEntry("manifest.json")).readBytes().decodeToString())
            assertTrue(zip.getEntry("icon.png") != null)
            assertTrue(zip.getEntry("base.apk") != null)
        }

        val verification = SplitArchive.verify(output, minSdk = 26, workDir = Fixtures.tempDir("split-verify"))
        assertEquals(3, verification.parts.size)
        assertTrue(verification.allVerified)
        assertEquals(1, verification.distinctSigners.size)
        assertEquals(CertUtils.sha256Fingerprint(identity.certificate), verification.distinctSigners.first())
    }

    @Test
    fun `signed parts differ from the unsigned ones`() {
        val dir = Fixtures.tempDir("split-diff")
        val archive = splitArchive(dir)
        val (_, identity) = Fixtures.identity(dir)
        val output = File(dir, "signed.apks")

        SplitArchive.sign(
            archive, output, identity, SchemeOptions(), 26, Fixtures.tempDir("split-work2")
        )

        val originalBytes = ZipFile(archive).use { it.getInputStream(it.getEntry("base.apk")).readBytes() }
        val signedBytes = ZipFile(output).use { it.getInputStream(it.getEntry("base.apk")).readBytes() }
        assertNotEquals(originalBytes.size, 0)
        assertFalse(originalBytes.contentEquals(signedBytes))
    }

    @Test
    fun `archive with a broken part fails without leaving an output`() {
        val dir = Fixtures.tempDir("split-broken")
        val archive = File(dir, "broken.apk.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("base.apk"))
            zip.write(ByteArray(2048) { 0x11 })
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("split.apk"))
            zip.write(Fixtures.apkFile(dir, "ok.apk").readBytes())
            zip.closeEntry()
        }
        val (_, identity) = Fixtures.identity(dir)
        val output = File(dir, "out.zip")
        try {
            SplitArchive.sign(archive, output, identity, SchemeOptions(), 26, Fixtures.tempDir("split-work3"))
        } catch (e: SigningException) {
            assertTrue(e.message!!.contains("base.apk"))
        }
        assertFalse(output.exists())
    }
}
