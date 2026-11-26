package dev.signiffia.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * [SyncTransport] backed by `HttpURLConnection` — available on both the JVM and Android with
 * no additional dependency. TLS is required (the client only accepts https). The session
 * token is sent as an Authorization header; request/response bodies are opaque bytes.
 *
 * Requires the INTERNET permission on Android; synchronization remains an explicit user
 * opt-in and the app stays fully offline by default.
 */
class HttpSyncTransport(private val baseUrl: String) : SyncTransport {

    init {
        require(baseUrl.startsWith("https://")) {
            "Server mora koristiti HTTPS (bezbedna veza)."
        }
    }

    override fun request(
        method: String,
        path: String,
        body: ByteArray,
        authToken: String?,
    ): HttpResponse {
        val connection = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            doInput = true
            if (authToken != null) {
                setRequestProperty("Authorization", "Bearer $authToken")
            }
            if (body.isNotEmpty()) {
                doOutput = true
                setRequestProperty("Content-Type", "application/octet-stream")
            }
        }
        try {
            if (body.isNotEmpty()) {
                val output: OutputStream = connection.outputStream
                output.use { it.write(body) }
            }
            val status = connection.responseCode
            val stream: InputStream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: ByteArrayInputStream(ByteArray(0))
            }
            val responseBody = readAll(stream)
            return HttpResponse(status, responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private fun readAll(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
