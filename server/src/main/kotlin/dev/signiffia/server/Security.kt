package dev.signiffia.server

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Security primitives shared by the server. None of these ever produce or log plaintext
 * secrets: the bearer token is generated here and only its SHA-256 hash is persisted.
 */
object Security {

    private val rng = SecureRandom()

    fun randomBytes(count: Int): ByteArray = ByteArray(count).also { rng.nextBytes(it) }

    /** Cryptographically random URL-safe ID (16 bytes → 22 chars). */
    fun randomId(bytes: Int = 16): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(bytes))

    /** A new 256-bit bearer session token (returned to the client once, in the clear). */
    fun generateSessionToken(): String = randomId(32)

    /**
     * The server stores only this hash, so a database leak exposes no usable bearer tokens.
     * The client's token is hashed again on every request and matched against the stored hash.
     */
    fun tokenHash(token: String): String = sha256Hex(token.toByteArray(Charsets.UTF_8))

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean =
        a.size == b.size && MessageDigest.isEqual(a, b)

    fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun decodeBase64Url(value: String): ByteArray =
        Base64.getUrlDecoder().decode(value)
}
