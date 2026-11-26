package dev.signiffia.server

import io.ktor.utils.io.ByteReadChannel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Dependency-free binary wire codec shared by the client and server. Big-endian, length-
 * prefixed. Must stay byte-for-byte compatible with the Android app's `WireCodec`.
 */
object Wire {
    private const val MAX_BYTES = 64 * 1024 * 1024

    class Writer {
        private val out = ByteArrayOutputStream()
        fun int(v: Int) = apply { repeat(4) { i -> out.write((v ushr (24 - 8 * i)) and 0xFF) } }
        fun long(v: Long) = apply { for (s in 56 downTo 0 step 8) out.write(((v ushr s) and 0xFF).toInt()) }
        fun bytes(b: ByteArray) = apply { int(b.size); out.write(b) }
        fun string(s: String) = bytes(s.toByteArray(StandardCharsets.UTF_8))
        fun toBytes() = out.toByteArray()
    }

    class Reader(bytes: ByteArray) {
        private val input = ByteArrayInputStream(bytes)
        fun int(): Int {
            val b = IntArray(4) { input.read() }
            if (b.any { it < 0 }) throw IllegalArgumentException("truncated")
            return (b[0] shl 24) or (b[1] shl 16) or (b[2] shl 8) or b[3]
        }
        fun long(): Long {
            var v = 0L
            repeat(8) { v = (v shl 8) or (input.read().toLong() and 0xFF) }
            return v
        }
        fun bytes(): ByteArray {
            val n = int()
            if (n < 0 || n > MAX_BYTES) throw IllegalArgumentException("too large")
            val b = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = input.read(b, off, n - off)
                if (r <= 0) throw IllegalArgumentException("truncated")
                off += r
            }
            return b
        }
        fun string() = String(bytes(), StandardCharsets.UTF_8)
    }
}

/** Reads the whole request body as bytes (bounded at 64 MiB). */
suspend fun ByteReadChannel.readBody(): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val read = readAvailable(buffer, 0, buffer.size)
        if (read == -1) break
        out.write(buffer, 0, read)
        if (out.size() > MAX_BODY_BYTES) throw IllegalArgumentException("body too large")
    }
    return out.toByteArray()
}

private const val MAX_BODY_BYTES = 64 * 1024 * 1024
