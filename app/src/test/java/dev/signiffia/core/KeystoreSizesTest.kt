package dev.signiffia.core

import dev.signiffia.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Covers every supported key algorithm/size combination end to end (generate + sign). */
class KeystoreSizesTest {

    @Test
    fun `every RSA key size generates and signs`() {
        for (size in intArrayOf(2048, 3072, 4096)) {
            val dir = Fixtures.tempDir("rsa-$size")
            val (_, identity) = Fixtures.identity(dir, alias = "rsa$size", keyAlgorithm = "RSA", keySize = size)
            assertEquals("RSA", identity.certificate.publicKey.algorithm)
            assertTrue(CertUtils.publicKeyDescription(identity.certificate).startsWith("RSA $size"))

            val outcome = SigningEngine.signApk(
                Fixtures.apkFile(dir), File(dir, "signed.apk"), identity, SchemeOptions(), minSdk = 26
            )
            assertTrue("RSA $size must sign", outcome.report.verified)
        }
    }

    @Test
    fun `every EC curve generates and signs`() {
        for (size in intArrayOf(256, 384, 521)) {
            val dir = Fixtures.tempDir("ec-$size")
            val (_, identity) = Fixtures.identity(dir, alias = "ec$size", keyAlgorithm = "EC", keySize = size)
            assertEquals("EC", identity.certificate.publicKey.algorithm)
            assertTrue(CertUtils.publicKeyDescription(identity.certificate).startsWith("EC $size"))

            val outcome = SigningEngine.signApk(
                Fixtures.apkFile(dir), File(dir, "signed.apk"), identity,
                SchemeOptions(v1 = false, v2 = true, v3 = true), minSdk = 26
            )
            assertTrue("EC $size must sign", outcome.report.verified)
        }
    }
}
