package dev.signiffia.server

import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

/**
 * Signiffia sync server entry point.
 *
 * The server is an untrusted relay: it stores only ciphertext and metadata. Storage is
 * PostgreSQL by default (see README for configuration and deployment). A self-check runs on
 * startup so the process refuses to serve when the database is unreachable or unmigrated.
 */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0") { module() }.start(wait = true)
}

fun Application.module() {
    val store = createStore()
    store.check() // fail fast if the database is unreachable
    val core = ServerCore(store)
    val webauthn = WebAuthnService(
        store = store,
        // The relying-party id and origin MUST match the served HTTPS domain in production.
        rpId = System.getenv("SIGNIFFIA_RP_ID") ?: "localhost",
        origin = System.getenv("SIGNIFFIA_ORIGIN") ?: "http://localhost:8080",
    )
    configureRoutes(core, webauthn)
}

/** Builds the persistent PostgreSQL store, or an in-memory store for local development. */
private fun createStore(): Store {
    val mode = System.getenv("SIGNIFFIA_STORE") ?: "postgres"
    return if (mode.equals("memory", ignoreCase = true)) {
        InMemoryStore()
    } else {
        PostgresStore.create()
    }
}
