package dev.signiffia.account

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Argon2id password-based key derivation for the *account* authentication credential.
 *
 * Important separation: the account password is used ONLY to authenticate to the Signiffia
 * sync server. It is NEVER used to derive the vault Master Vault Key — the MVK is an
 * independent random key, so possession of the account password proves nothing about the
 * vault (security requirement: auth credentials and vault keys are separate).
 *
 * Argon2id is provided by the already-bundled BouncyCastle (MIT-style licence); no new
 * dependency is introduced.
 */
object PasswordKdf {

    const val SALT_BYTES = 16
    const val KEY_BYTES = 32

    data class Params(
        val memoryKiB: Int = 19 * 1024, // ~19 MiB, OWASP minimum for Argon2id
        val iterations: Int = 2,
        val parallelism: Int = 1,
    )

    private val rng = SecureRandom()

    fun generateSalt(): ByteArray = ByteArray(SALT_BYTES).also { rng.nextBytes(it) }

    fun hash(password: CharArray, salt: ByteArray, params: Params = Params()): ByteArray {
        if (salt.isEmpty()) throw IllegalArgumentException("Salt ne sme biti prazan.")
        val builder = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withSalt(salt)
            .withParallelism(params.parallelism)
            .withMemoryAsKB(params.memoryKiB)
            .withIterations(params.iterations)
        val generator = Argon2BytesGenerator()
        generator.init(builder.build())
        val out = ByteArray(KEY_BYTES)
        generator.generateBytes(password, out)
        return out
    }

    /** Constant-time comparison of a candidate hash against an expected hash. */
    fun verify(expected: ByteArray, candidate: ByteArray): Boolean {
        if (expected.size != candidate.size) return false
        return MessageDigest.isEqual(expected, candidate)
    }
}
