package dev.signiffia.account

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordKdfTest {

    // Small params keep the JVM test fast; the code path is identical to production.
    private val fast = PasswordKdf.Params(memoryKiB = 32, iterations = 2, parallelism = 1)

    @Test
    fun `hash is deterministic for the same inputs`() {
        val salt = PasswordKdf.generateSalt()
        val password = "correct horse battery".toCharArray()
        val h1 = PasswordKdf.hash(password, salt, fast)
        val h2 = PasswordKdf.hash(password, salt, fast)
        assertTrue(h1.contentEquals(h2))
        assertTrue(PasswordKdf.verify(h1, h2))
    }

    @Test
    fun `different passwords produce different hashes`() {
        val salt = PasswordKdf.generateSalt()
        val h1 = PasswordKdf.hash("password-one".toCharArray(), salt, fast)
        val h2 = PasswordKdf.hash("password-two".toCharArray(), salt, fast)
        assertFalse(h1.contentEquals(h2))
    }

    @Test
    fun `different salts produce different hashes`() {
        val password = "password-one".toCharArray()
        val h1 = PasswordKdf.hash(password, PasswordKdf.generateSalt(), fast)
        val h2 = PasswordKdf.hash(password, PasswordKdf.generateSalt(), fast)
        assertFalse(h1.contentEquals(h2))
    }

    @Test
    fun `output is 32 bytes`() {
        val hash = PasswordKdf.hash("password".toCharArray(), PasswordKdf.generateSalt(), fast)
        assertTrue(hash.size == 32)
    }

    @Test
    fun `verify rejects mismatched length`() {
        val h = PasswordKdf.hash("password".toCharArray(), PasswordKdf.generateSalt(), fast)
        assertFalse(PasswordKdf.verify(h, ByteArray(16)))
    }

    @Test
    fun `salts are unique and 16 bytes`() {
        val s1 = PasswordKdf.generateSalt()
        val s2 = PasswordKdf.generateSalt()
        assertTrue(s1.size == 16)
        assertNotEquals(s1.contentHashCode(), s2.contentHashCode())
    }
}
