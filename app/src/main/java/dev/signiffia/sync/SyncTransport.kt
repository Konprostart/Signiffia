package dev.signiffia.sync

/** HTTP response as returned by a transport: status code plus raw body bytes. */
data class HttpResponse(val status: Int, val body: ByteArray)

/**
 * Pluggable HTTP transport. The client never depends on a concrete HTTP stack, so the
 * whole sync protocol can be exercised with an in-memory fake in unit tests and with
 * [HttpSyncTransport] (HttpURLConnection) on the device.
 */
interface SyncTransport {
    fun request(
        method: String,
        path: String,
        body: ByteArray = ByteArray(0),
        authToken: String? = null,
    ): HttpResponse
}
