package dev.signiffia.server

import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Reference Signiffia sync server (Ktor/Netty). It is an untrusted relay: it stores only
 * ciphertext and metadata, never the Master Vault Key, recovery key, private keys or
 * keystore passwords. See README.md for the full protocol, SQL schema and deployment guide.
 *
 * This is a self-contained reference implementation for local development and testing; it is
 * not part of the Android app build.
 */

// --------------------------------------------------------------------- wire codec
private class Writer {
    private val out = ByteArrayOutputStream()
    fun int(v: Int) = apply { repeat(4) { i -> out.write((v ushr (24 - 8 * i)) and 0xFF) } }
    fun long(v: Long) = apply { for (s in 56 downTo 0 step 8) out.write(((v ushr s) and 0xFF).toInt()) }
    fun bytes(b: ByteArray) = apply { int(b.size); out.write(b) }
    fun string(s: String) = bytes(s.toByteArray(StandardCharsets.UTF_8))
    fun toBytes() = out.toByteArray()
}

private class Reader(bytes: ByteArray) {
    private val in_ = ByteArrayInputStream(bytes)
    fun int(): Int {
        val b = IntArray(4) { in_.read() }
        if (b.any { it < 0 }) throw IllegalArgumentException("truncated")
        return (b[0] shl 24) or (b[1] shl 16) or (b[2] shl 8) or b[3]
    }
    fun long(): Long {
        var v = 0L
        repeat(8) { v = (v shl 8) or (in_.read().toLong() and 0xFF) }
        return v
    }
    fun bytes(): ByteArray {
        val n = int()
        if (n < 0 || n > 64 * 1024 * 1024) throw IllegalArgumentException("too large")
        val b = ByteArray(n)
        var off = 0
        while (off < n) { val r = in_.read(b, off, n - off); if (r <= 0) throw IllegalArgumentException("truncated"); off += r }
        return b
    }
    fun string() = String(bytes(), StandardCharsets.UTF_8)
}

// --------------------------------------------------------------------- storage
private val rng = SecureRandom()

private fun randomId(bytes: Int = 16): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also { rng.nextBytes(it) })

private fun constantTimeEquals(a: ByteArray, b: ByteArray) =
    a.size == b.size && MessageDigest.isEqual(a, b)

private class Store {
    data class User(val userId: String, val username: String, val verifier: ByteArray, val salt: ByteArray)
    data class Session(val userId: String, val deviceId: String, val expiresAt: Long)
    data class Device(val deviceId: String, val userId: String, val name: String, val platform: String,
                      val createdAt: Long, var lastSeenAt: Long, var revoked: Boolean)
    data class Vault(val vaultVersion: Long, val epoch: Long, val deviceId: String, val createdAt: Long,
                     val recordsSha256: String, val nonce: ByteArray, val ciphertext: ByteArray,
                     val wrappedMvkRecovery: ByteArray)
    data class Ticket(val devicePublicKey: ByteArray, val serverPublicKey: ByteArray, val nonce: ByteArray,
                      val expiresAt: Long, var used: Boolean)

    val users = ConcurrentHashMap<String, User>()
    val usersByName = ConcurrentHashMap<String, User>()
    val sessions = ConcurrentHashMap<String, Session>()
    val devices = ConcurrentHashMap<String, Device>()
    val vaults = ConcurrentHashMap<String, Vault>() // userId -> latest vault
    val tickets = ConcurrentHashMap<String, Ticket>()
    val epochByUser = ConcurrentHashMap<String, Long>()
}

// --------------------------------------------------------------------- app
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0") { module() }.start(wait = true)
}

fun Application.module() {
    val store = Store()

    routing {
        get("/health") { call.respondText("ok") }

        // ------------------------------------------------------------- auth
        get("/auth/salt") {
            val body = call.receiveChannel().toByteArray()
            val username = Reader(body).string()
            val user = store.usersByName[username]
            if (user == null) { call.respondText("nepoznat korisnik", status = io.ktor.http.HttpStatusCode.NotFound); return@get }
            call.respondBytes(Writer().bytes(user.salt).toBytes())
        }

        post("/auth/register") {
            val r = Reader(call.receiveChannel().toByteArray())
            val username = r.string(); val salt = r.bytes(); val verifier = r.bytes()
            if (store.usersByName.containsKey(username)) {
                call.respondText("korisnik već postoji", status = io.ktor.http.HttpStatusCode.Conflict); return@post
            }
            val user = Store.User(randomId(), username, verifier, salt)
            store.users[user.userId] = user
            store.usersByName[username] = user
            call.respondBytes(ByteArray(0))
        }

        post("/auth/login") {
            val r = Reader(call.receiveChannel().toByteArray())
            val username = r.string(); val verifier = r.bytes()
            val user = store.usersByName[username]
            if (user == null || !constantTimeEquals(user.verifier, verifier)) {
                call.respondText("pogrešni podaci", status = io.ktor.http.HttpStatusCode.Unauthorized); return@post
            }
            val deviceId = randomId()
            val token = randomId(32)
            store.sessions[token] = Store.Session(user.userId, deviceId, System.currentTimeMillis() + 30L * 24 * 3600 * 1000)
            store.devices[deviceId] = Store.Device(deviceId, user.userId, "Novi uređaj", "android",
                System.currentTimeMillis(), System.currentTimeMillis(), false)
            call.respondBytes(Writer().string(user.userId).string(token).string(deviceId).toBytes())
        }

        // ------------------------------------------------------------- vault
        put("/vault/object") {
            val session = authenticate(store, call.request.headers["Authorization"])
                ?: return@put call.respondText("neovlašćeno", status = io.ktor.http.HttpStatusCode.Unauthorized)
            val r = Reader(call.receiveChannel().toByteArray())
            val version = r.long(); val epoch = r.long(); val deviceId = r.string(); val createdAt = r.long()
            val sha = r.string(); val nonce = r.bytes(); val ciphertext = r.bytes(); val wrapped = r.bytes()
            val existing = store.vaults[session.userId]
            if (existing != null && version < existing.vaultVersion) {
                call.respondText("zastarela verzija", status = io.ktor.http.HttpStatusCode.Conflict); return@put
            }
            val newEpoch = maxOf(epoch, store.epochByUser[session.userId] ?: 1L)
            store.vaults[session.userId] = Store.Vault(version, newEpoch, deviceId, createdAt, sha, nonce, ciphertext, wrapped)
            call.respondBytes(ByteArray(0))
        }

        get("/vault/object") {
            val session = authenticate(store, call.request.headers["Authorization"])
                ?: return@get call.respondText("neovlašćeno", status = io.ktor.http.HttpStatusCode.Unauthorized)
            val v = store.vaults[session.userId]
                ?: return@get call.respondText("nema", status = io.ktor.http.HttpStatusCode.NotFound)
            call.respondBytes(Writer().long(v.vaultVersion).long(v.epoch).string(v.deviceId).long(v.createdAt)
                .string(v.recordsSha256).bytes(v.nonce).bytes(v.ciphertext).bytes(v.wrappedMvkRecovery).toBytes())
        }

        // ------------------------------------------------------------- devices
        get("/devices") {
            val session = authenticate(store, call.request.headers["Authorization"])
                ?: return@get call.respondText("neovlašćeno", status = io.ktor.http.HttpStatusCode.Unauthorized)
            val mine = store.devices.values.filter { it.userId == session.userId }
            val w = Writer().int(mine.size)
            mine.forEach { d -> w.string(d.deviceId).string(d.name).string(d.platform).long(d.createdAt).long(d.lastSeenAt).int(if (d.revoked) 1 else 0) }
            call.respondBytes(w.toBytes())
        }

        post("/devices/revoke") {
            val session = authenticate(store, call.request.headers["Authorization"])
                ?: return@post call.respondText("neovlašćeno", status = io.ktor.http.HttpStatusCode.Unauthorized)
            val deviceId = Reader(call.receiveChannel().toByteArray()).string()
            val device = store.devices[deviceId]
            if (device == null || device.userId != session.userId) {
                call.respondText("nema uređaja", status = io.ktor.http.HttpStatusCode.NotFound); return@post
            }
            device.revoked = true
            val newEpoch = (store.epochByUser[session.userId] ?: 1L) + 1
            store.epochByUser[session.userId] = newEpoch
            call.respondBytes(Writer().long(newEpoch).toBytes())
        }

        // ------------------------------------------------------------- enrollment (relay)
        post("/enroll/begin") {
            val session = authenticate(store, call.request.headers["Authorization"])
                ?: return@post call.respondText("neovlašćeno", status = io.ktor.http.HttpStatusCode.Unauthorized)
            val devicePublicKey = Reader(call.receiveChannel().toByteArray()).bytes()
            val ticketId = randomId()
            val serverPublicKey = ByteArray(65).also { rng.nextBytes(it) } // relayed public key (placeholder)
            val nonce = ByteArray(16).also { rng.nextBytes(it) }
            val expires = System.currentTimeMillis() + 5 * 60 * 1000L
            store.tickets[ticketId] = Store.Ticket(devicePublicKey, serverPublicKey, nonce, expires, used = false)
            call.respondBytes(Writer().string(ticketId).long(expires).bytes(serverPublicKey).bytes(nonce).toBytes())
        }

        post("/enroll/confirm") {
            val session = authenticate(store, call.request.headers["Authorization"])
                ?: return@post call.respondText("neovlašćeno", status = io.ktor.http.HttpStatusCode.Unauthorized)
            val r = Reader(call.receiveChannel().toByteArray())
            val ticketId = r.string(); r.bytes() // wrapped MVK for the new device (relayed, not stored)
            val ticket = store.tickets[ticketId]
            if (ticket == null || ticket.used || ticket.expiresAt < System.currentTimeMillis()) {
                call.respondText("neispravan tiket", status = io.ktor.http.HttpStatusCode.BadRequest); return@post
            }
            ticket.used = true
            call.respondBytes(ByteArray(0))
        }
    }
}

private fun authenticate(store: Store, header: String?): Store.Session? {
    val token = header?.removePrefix("Bearer ") ?: return null
    val session = store.sessions[token] ?: return null
    if (session.expiresAt < System.currentTimeMillis()) return null
    val device = store.devices[session.deviceId]
    if (device?.revoked == true) return null
    device?.lastSeenAt = System.currentTimeMillis()
    return session
}

private suspend fun io.ktor.server.request.ApplicationReceiveChannel.toByteArray(): ByteArray {
    val out = ByteArrayOutputStream()
    val channel = this
    while (true) {
        val packet = channel.readRemaining(64 * 1024)
        if (packet.isEmpty) break
        out.write(packet.readBytes())
    }
    return out.toByteArray()
}
