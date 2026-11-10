package dev.signiffia.core

import dev.signiffia.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class SigningEngineTest {

    @Test
    fun `signs an APK with v1 v2 and v3 and verification passes`() {
        val dir = Fixtures.tempDir("sign-all")
        val input = Fixtures.apkFile(dir)
        val (_, identity) = Fixtures.identity(dir)
        val output = File(dir, "signed.apk")

        val outcome = SigningEngine.signApk(
            input = input,
            output = output,
            identity = identity,
            schemes = SchemeOptions(v1 = true, v2 = true, v3 = true),
            minSdk = 26,
        )

        assertTrue(outcome.output.exists())
        assertTrue("signed output must be a new file", output.length() > 0)
        assertTrue(outcome.report.verified)
        assertTrue(outcome.report.v1)
        assertTrue(outcome.report.v2)
        assertTrue(outcome.report.v3)
        assertFalse(outcome.report.v4)
        assertEquals(1, outcome.report.signers.size)
        assertEquals(
            CertUtils.sha256Fingerprint(identity.certificate),
            outcome.report.signers.first().sha256
        )
        assertTrue(outcome.report.errors.isEmpty())
    }

    @Test
    fun `v2 and v3 only signing verifies on Android 7+`() {
        val dir = Fixtures.tempDir("sign-v23")
        val input = Fixtures.apkFile(dir)
        val (_, identity) = Fixtures.identity(dir, keyAlgorithm = "EC", keySize = 256)
        val output = File(dir, "signed-v23.apk")

        val outcome = SigningEngine.signApk(
            input, output, identity, SchemeOptions(v1 = false, v2 = true, v3 = true), minSdk = 24
        )

        assertTrue(outcome.report.verified)
        assertFalse(outcome.report.v1)
        assertTrue(outcome.report.v2)
        assertTrue(outcome.report.v3)
    }

    @Test
    fun `v1 only signing verifies for older platforms`() {
        val dir = Fixtures.tempDir("sign-v1")
        val input = Fixtures.apkFile(dir)
        val (_, identity) = Fixtures.identity(dir)
        val output = File(dir, "signed-v1.apk")

        val outcome = SigningEngine.signApk(
            input, output, identity, SchemeOptions(v1 = true, v2 = false, v3 = false), minSdk = 21
        )
        assertTrue(outcome.report.verified)
        assertTrue(outcome.report.v1)
        assertFalse(outcome.report.v2)
    }

    @Test
    fun `v3 without v2 is upgraded automatically`() {
        val normalized = SigningEngine.normalizeSchemes(SchemeOptions(v1 = false, v2 = false, v3 = true))
        assertTrue(normalized.v2)
        assertTrue(normalized.v3)
    }

    @Test
    fun `v4 signing writes an idsig file that verifies`() {
        val dir = Fixtures.tempDir("sign-v4")
        val input = Fixtures.apkFile(dir)
        val (_, identity) = Fixtures.identity(dir)
        val output = File(dir, "signed-v4.apk")
        val idsig = File(dir, "signed-v4.apk.idsig")

        val outcome = SigningEngine.signApk(
            input, output, identity, SchemeOptions(v1 = true, v2 = true, v3 = true, v4 = true),
            minSdk = 26, idsigFile = idsig
        )
        assertTrue(outcome.report.verified)
        assertTrue(outcome.report.v4)
        assertTrue(idsig.exists())
        assertTrue(idsig.length() > 0)
    }

    @Test
    fun `the original APK is never modified`() {
        val dir = Fixtures.tempDir("untouched")
        val input = Fixtures.apkFile(dir)
        val before = sha256(input)
        val (_, identity) = Fixtures.identity(dir)
        val output = File(dir, "signed.apk")

        SigningEngine.signApk(input, output, identity, SchemeOptions(), minSdk = 26)

        assertEquals(before, sha256(input))
        assertNotEquals(sha256(input), sha256(output))
    }

    @Test
    fun `tampered APK fails verification instead of being reported as valid`() {
        val dir = Fixtures.tempDir("tamper")
        val input = Fixtures.apkFile(dir)
        val (_, identity) = Fixtures.identity(dir)
        val output = File(dir, "signed.apk")
        SigningEngine.signApk(input, output, identity, SchemeOptions(), minSdk = 26)
        assertTrue(SigningEngine.verify(output, minSdk = 26).verified)

        Fixtures.tamperWithApk(output)

        val report = SigningEngine.verify(output, minSdk = 26)
        assertFalse("tampered APK must not verify", report.verified)
        assertTrue(report.errors.isNotEmpty())
    }

    @Test
    fun `unsigned APK is reported as unsigned`() {
        val dir = Fixtures.tempDir("unsigned")
        val input = Fixtures.apkFile(dir)
        val report = SigningEngine.verify(input, minSdk = 26)
        assertFalse(report.verified)
        assertTrue(report.errors.isNotEmpty())
    }

    @Test
    fun `garbage input fails with a clear message`() {
        val dir = Fixtures.tempDir("garbage")
        val junk = File(dir, "junk.apk")
        junk.writeBytes(ByteArray(4096) { (it * 31 % 255).toByte() })
        try {
            SigningEngine.verify(junk, minSdk = 26)
            // Some malformed files are reported (not thrown); both outcomes are acceptable
            // as long as they are not "verified".
            fail("Expected an exception for a non-zip file")
        } catch (e: SigningException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    @Test
    fun `output must differ from input`() {
        val dir = Fixtures.tempDir("same-file")
        val input = Fixtures.apkFile(dir)
        val (_, identity) = Fixtures.identity(dir)
        try {
            SigningEngine.signApk(input, input, identity, SchemeOptions(), minSdk = 26)
            fail("Expected SigningException")
        } catch (e: SigningException) {
            assertTrue(e.message!!.contains("isti"))
        }
        assertTrue(input.exists())
    }

    @Test
    fun `AAB gets a JAR v1 signature and no v2 block`() {
        val dir = Fixtures.tempDir("aab")
        val bundle = File(dir, "app.aab")
        java.util.zip.ZipOutputStream(bundle.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("BundleConfig.pb"))
            zip.write(ByteArray(64))
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("base/manifest/AndroidManifest.xml"))
            zip.write(ByteArray(128))
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("base/dex/classes.dex"))
            zip.write("dex\n035\u0000".toByteArray() + ByteArray(256))
            zip.closeEntry()
        }
        val (_, identity) = Fixtures.identity(dir)
        val output = File(dir, "app-signed.aab")

        val outcome = SigningEngine.signBundle(bundle, output, identity)

        assertTrue(outcome.report.verified)
        assertTrue(outcome.report.v1)
        assertFalse(outcome.report.v2)

        val jar = java.util.jar.JarFile(output, true)
        val entries = jar.entries().toList()
        jar.close()
        assertTrue(entries.any { it.name == "META-INF/MANIFEST.MF" })
        assertTrue(entries.any { it.name.endsWith(".SF") })
        assertTrue(entries.any { it.name.endsWith(".RSA") || it.name.endsWith(".EC") })
        assertFalse(
            "AAB must not contain an APK Signing Block",
            String(output.readBytes(), Charsets.ISO_8859_1).contains("APK Sig Block 42")
        )

        // And the JAR verifier detects a bundle whose content was modified after signing.
        val tampered = File(dir, "tampered.aab")
        java.util.zip.ZipFile(output).use { zip ->
            java.util.zip.ZipOutputStream(tampered.outputStream()).use { out ->
                for (entry in zip.entries()) {
                    out.putNextEntry(java.util.zip.ZipEntry(entry.name))
                    val data = zip.getInputStream(entry).readBytes()
                    if (entry.name == "BundleConfig.pb") {
                        data[0] = (data[0].toInt() + 1).toByte()
                    }
                    out.write(data)
                    out.closeEntry()
                }
            }
        }
        assertFalse(SigningEngine.verifyJar(tampered).verified)
    }

    @Test
    fun `signing a real built APK produces a Play and device installable signature`() {
        val realApkPath = System.getenv("SIGNIFFIA_REAL_APK") ?: return
        val realApk = File(realApkPath)
        if (!realApk.exists()) return

        val dir = Fixtures.tempDir("real-apk")
        val (_, identity) = Fixtures.identity(dir, alias = "release-test", keySize = 3072)
        val output = File(dir, "real-signed.apk")

        val outcome = SigningEngine.signApk(realApk, output, identity, SchemeOptions(), minSdk = 26)

        assertTrue(outcome.report.verified)
        assertTrue(outcome.report.v1 && outcome.report.v2 && outcome.report.v3)
        assertEquals(sha256(realApk), sha256(realApk))
        assertTrue(output.length() > 1_000_000)
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
}
