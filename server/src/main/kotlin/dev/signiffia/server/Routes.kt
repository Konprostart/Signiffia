package dev.signiffia.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing

/**
 * HTTP adapters. Each handler is a thin translation from the binary wire format to [ServerCore];
 * all security decisions live in [ServerCore] so they are unit-testable without a server.
 * No handler ever logs a request/response body (they may carry ciphertext).
 */
fun Application.configureRoutes(core: ServerCore, webauthn: WebAuthnService) {
    routing {
        get("/health") { call.respondText("ok") }

        // ------------------------------------------------------------- auth

        get("/auth/salt") {
            val r = Wire.Reader(call.receiveChannel().readBody())
            val username = r.string()
            val salt = core.fetchSalt(username)
            if (salt == null) {
                call.respondText("nepoznat korisnik", status = HttpStatusCode.NotFound); return@get
            }
            call.respondBytes(Wire.Writer().bytes(salt).toBytes())
        }

        post("/auth/register") {
            if (!core.allowRegister(keyOf(call))) {
                call.respondText("previše pokušaja", status = HttpStatusCode.TooManyRequests); return@post
            }
            val r = Wire.Reader(call.receiveChannel().readBody())
            val username = r.string(); val salt = r.bytes(); val verifier = r.bytes()
            val error = core.register(username, salt, verifier)
            if (error != null) {
                call.respondText(error, status = HttpStatusCode.Conflict); return@post
            }
            call.respondBytes(ByteArray(0))
        }

        post("/auth/login") {
            if (!core.allowLogin(keyOf(call))) {
                call.respondText("previše pokušaja", status = HttpStatusCode.TooManyRequests); return@post
            }
            val r = Wire.Reader(call.receiveChannel().readBody())
            val username = r.string(); val verifier = r.bytes()
            val session = core.login(username, verifier)
            if (session == null) {
                call.respondText("pogrešni podaci", status = HttpStatusCode.Unauthorized); return@post
            }
            call.respondBytes(
                Wire.Writer().string(session.userId).string(session.sessionToken).string(session.deviceId).toBytes()
            )
        }

        // ------------------------------------------------------------- vault

        get("/vault/meta") {
            val session = core.authenticate(call.request.headers["Authorization"])
                ?: return@get call.respondText("neovlašćeno", status = HttpStatusCode.Unauthorized)
            val meta = core.vaultMeta(session)
            call.respondBytes(Wire.Writer().long(meta.vaultVersion).long(meta.epoch).toBytes())
        }

        put("/vault/object") {
            val session = core.authenticate(call.request.headers["Authorization"])
                ?: return@put call.respondText("neovlašćeno", status = HttpStatusCode.Unauthorized)
            val r = Wire.Reader(call.receiveChannel().readBody())
            val incoming = VaultRecord(
                vaultVersion = r.long(),
                epoch = r.long(),
                deviceId = r.string(),
                createdAt = r.long(),
                recordsSha256 = r.string(),
                nonce = r.bytes(),
                ciphertext = r.bytes(),
                wrappedMvkRecovery = r.bytes(),
            )
            when (val result = core.putVault(session, incoming)) {
                is ServerCore.VaultResult.Accepted -> call.respondBytes(ByteArray(0))
                is ServerCore.VaultResult.Rejected -> call.respondText(
                    result.reason,
                    status = HttpStatusCode.Conflict,
                )
            }
        }

        get("/vault/object") {
            val session = core.authenticate(call.request.headers["Authorization"])
                ?: return@get call.respondText("neovlašćeno", status = HttpStatusCode.Unauthorized)
            val v = core.getVault(session)
                ?: return@get call.respondText("nema", status = HttpStatusCode.NotFound)
            call.respondBytes(
                Wire.Writer().long(v.vaultVersion).long(v.epoch).string(v.deviceId).long(v.createdAt)
                    .string(v.recordsSha256).bytes(v.nonce).bytes(v.ciphertext).bytes(v.wrappedMvkRecovery).toBytes()
            )
        }

        // ------------------------------------------------------------- devices

        get("/devices") {
            val session = core.authenticate(call.request.headers["Authorization"])
                ?: return@get call.respondText("neovlašćeno", status = HttpStatusCode.Unauthorized)
            val mine = core.listDevices(session)
            val w = Wire.Writer().int(mine.size)
            mine.forEach { d ->
                w.string(d.deviceId).string(d.name).string(d.platform).long(d.createdAt).long(d.lastSeenAt)
                    .int(if (d.revoked) 1 else 0)
            }
            call.respondBytes(w.toBytes())
        }

        post("/devices/revoke") {
            val session = core.authenticate(call.request.headers["Authorization"])
                ?: return@post call.respondText("neovlašćeno", status = HttpStatusCode.Unauthorized)
            val deviceId = Wire.Reader(call.receiveChannel().readBody()).string()
            val newEpoch = core.revokeDevice(session, deviceId)
            if (newEpoch == null) {
                call.respondText("nema uređaja", status = HttpStatusCode.NotFound); return@post
            }
            call.respondBytes(Wire.Writer().long(newEpoch).toBytes())
        }

        // ------------------------------------------------------------- enrollment

        post("/enroll/begin") {
            val session = core.authenticate(call.request.headers["Authorization"])
                ?: return@post call.respondText("neovlašćeno", status = HttpStatusCode.Unauthorized)
            val devicePublicKey = Wire.Reader(call.receiveChannel().readBody()).bytes()
            val ticket = core.enrollBegin(session, devicePublicKey)
            call.respondBytes(
                Wire.Writer().string(ticket.ticketId).long(ticket.expiresAtMillis).bytes(ticket.nonce).toBytes()
            )
        }

        post("/enroll/confirm") {
            val session = core.authenticate(call.request.headers["Authorization"])
                ?: return@post call.respondText("neovlašćeno", status = HttpStatusCode.Unauthorized)
            val ticketId = Wire.Reader(call.receiveChannel().readBody()).string()
            if (!core.enrollConfirm(session, ticketId)) {
                call.respondText("neispravan tiket", status = HttpStatusCode.BadRequest); return@post
            }
            call.respondBytes(ByteArray(0))
        }

        // ------------------------------------------------------------- webauthn / passkey
        // Registration requires an authenticated session (the user adds a passkey to an
        // existing account). Authentication is unauthenticated and issues a session token.

        post("/webauthn/register/begin") {
            val session = core.authenticate(call.request.headers["Authorization"])
                ?: return@post call.respondText("neovlašćeno", status = HttpStatusCode.Unauthorized)
            val username = core.usernameOf(session)
                ?: return@post call.respondText("nepoznat korisnik", status = HttpStatusCode.NotFound)
            call.respondText(webauthn.startRegistration(session.userId, username))
        }

        post("/webauthn/register/complete") {
            val session = core.authenticate(call.request.headers["Authorization"])
                ?: return@post call.respondText("neovlašćeno", status = HttpStatusCode.Unauthorized)
            val json = String(call.receiveChannel().readBody(), Charsets.UTF_8)
            if (webauthn.finishRegistration(json) == null) {
                call.respondText("neispravan odgovor", status = HttpStatusCode.BadRequest); return@post
            }
            call.respondText("ok")
        }

        post("/webauthn/auth/begin") {
            val username = String(call.receiveChannel().readBody(), Charsets.UTF_8).trim()
            val options = webauthn.startAuthentication(username)
                ?: return@post call.respondText("nema passkey-ja", status = HttpStatusCode.NotFound)
            call.respondText(options)
        }

        post("/webauthn/auth/complete") {
            val json = String(call.receiveChannel().readBody(), Charsets.UTF_8)
            val session = webauthn.finishAuthentication(json)
                ?: return@post call.respondText("neispravan odgovor", status = HttpStatusCode.Unauthorized)
            call.respondText(
                """{"userId":"${session.userId}","sessionToken":"${session.sessionToken}","deviceId":"${session.deviceId}"}"""
            )
        }
    }
}

/**
 * Rate-limit key. Behind the documented reverse proxy this is the client IP (via
 * X-Forwarded-For / X-Real-IP); for a direct connection it falls back to a single shared
 * bucket, which still bounds total credential-guessing attempts against the server.
 */
private fun keyOf(call: io.ktor.server.application.ApplicationCall): String {
    val forwarded = call.request.headers["X-Forwarded-For"]
    if (!forwarded.isNullOrBlank()) return forwarded.substringBefore(',').trim()
    val realIp = call.request.headers["X-Real-IP"]
    if (!realIp.isNullOrBlank()) return realIp.trim()
    return "direct"
}
