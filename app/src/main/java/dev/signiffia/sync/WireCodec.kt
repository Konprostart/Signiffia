package dev.signiffia.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Minimal, dependency-free binary codec for the Signiffia sync wire protocol.
 *
 * Fields are big-endian length-prefixed. Binary fields (ciphertext, keys, nonces) are raw
 * bytes; strings are UTF-8. There is deliberately no JSON here: the integrity of the data
 * is provided by the vault's AES-GCM (with AAD binding version + epoch), not by the
 * transport encoding, and a hand-rolled format has zero external dependencies.
 */
object WireCodec {

    class Writer {
        private val out = ByteArrayOutputStream()

        fun int(value: Int): Writer {
            out.write((value ushr 24) and 0xFF)
            out.write((value ushr 16) and 0xFF)
            out.write((value ushr 8) and 0xFF)
            out.write(value and 0xFF)
            return this
        }

        fun long(value: Long): Writer {
            for (shift in 56 downTo 0 step 8) out.write(((value ushr shift) and 0xFF).toInt())
            return this
        }

        fun bytes(value: ByteArray): Writer {
            int(value.size)
            out.write(value)
            return this
        }

        fun string(value: String): Writer = bytes(value.toByteArray(StandardCharsets.UTF_8))

        fun stringList(values: List<String>): Writer {
            int(values.size)
            values.forEach { string(it) }
            return this
        }

        fun toBytes(): ByteArray = out.toByteArray()
    }

    class Reader(bytes: ByteArray) {
        private val input = ByteArrayInputStream(bytes)

        fun int(): Int {
            val b1 = input.read(); val b2 = input.read(); val b3 = input.read(); val b4 = input.read()
            if (b1 < 0 || b2 < 0 || b3 < 0 || b4 < 0) throw SyncException("Neispravan odgovor servera.")
            return (b1 shl 24) or (b2 shl 16) or (b3 shl 8) or b4
        }

        fun long(): Long {
            var value = 0L
            repeat(8) { value = (value shl 8) or (input.read().toLong() and 0xFF) }
            return value
        }

        fun bytes(limit: Int = MAX_BYTES): ByteArray {
            val length = int()
            if (length < 0 || length > limit) throw SyncException("Odgovor servera prelazi dozvoljenu veličinu.")
            val value = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val read = input.read(value, offset, length - offset)
                if (read <= 0) throw SyncException("Odgovor servera je oštećen.")
                offset += read
            }
            return value
        }

        fun string(): String = String(bytes(), StandardCharsets.UTF_8)

        fun stringList(): List<String> {
            val count = int()
            if (count < 0 || count > 10_000) throw SyncException("Neispravna lista u odgovoru servera.")
            return (0 until count).map { string() }
        }
    }
}

private const val MAX_BYTES = 64 * 1024 * 1024
