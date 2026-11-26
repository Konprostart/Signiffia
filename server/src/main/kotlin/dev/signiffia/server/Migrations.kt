package dev.signiffia.server

import java.sql.Connection

/**
 * Versioned SQL migrations for the server, embedded in Kotlin so they work identically under
 * `gradle run`, `gradle test` and the distribution jar. Migrations are additive and ordered;
 * each is applied at most once and recorded in a `schema_migrations` table, so upgrades never
 * lose data (see server/README.md).
 */
object MigrationScripts {

    /** Ordered migration scripts; the key is the migration number (applied in ascending order). */
    val scripts: List<String> = listOf(
        // V1 — initial schema (users, sessions, devices, vault, epochs, enrollment).
        """
        CREATE TABLE users (
            user_id    TEXT PRIMARY KEY,
            username   TEXT UNIQUE NOT NULL,
            verifier   BYTEA NOT NULL,
            salt       BYTEA NOT NULL,
            created_at BIGINT NOT NULL
        );

        CREATE TABLE sessions (
            token_hash  TEXT PRIMARY KEY,
            user_id     TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
            device_id   TEXT NOT NULL,
            created_at  BIGINT NOT NULL,
            expires_at  BIGINT NOT NULL
        );
        CREATE INDEX sessions_user_idx ON sessions(user_id);

        CREATE TABLE devices (
            device_id    TEXT PRIMARY KEY,
            user_id      TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
            name         TEXT NOT NULL,
            platform     TEXT NOT NULL,
            created_at   BIGINT NOT NULL,
            last_seen_at BIGINT NOT NULL,
            revoked      BOOLEAN NOT NULL DEFAULT FALSE
        );
        CREATE INDEX devices_user_idx ON devices(user_id);

        CREATE TABLE vault_objects (
            user_id              TEXT PRIMARY KEY REFERENCES users(user_id) ON DELETE CASCADE,
            vault_version        BIGINT NOT NULL,
            epoch                BIGINT NOT NULL,
            device_id            TEXT NOT NULL,
            created_at           BIGINT NOT NULL,
            records_sha256       TEXT NOT NULL,
            nonce                BYTEA NOT NULL,
            ciphertext           BYTEA NOT NULL,
            wrapped_mvk_recovery BYTEA NOT NULL
        );

        CREATE TABLE user_epochs (
            user_id TEXT PRIMARY KEY REFERENCES users(user_id) ON DELETE CASCADE,
            epoch   BIGINT NOT NULL DEFAULT 1
        );

        CREATE TABLE enroll_tokens (
            ticket_id         TEXT PRIMARY KEY,
            user_id           TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
            device_public_key BYTEA NOT NULL,
            nonce             BYTEA NOT NULL,
            expires_at        BIGINT NOT NULL,
            used              BOOLEAN NOT NULL DEFAULT FALSE
        );
        """.trimIndent(),
        // V2 — WebAuthn / passkey credentials and one-time challenges.
        """
        CREATE TABLE webauthn_credentials (
            credential_id TEXT PRIMARY KEY,
            user_id       TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
            record_json   TEXT NOT NULL,
            sign_count    BIGINT NOT NULL,
            created_at    BIGINT NOT NULL
        );
        CREATE INDEX webauthn_credentials_user_idx ON webauthn_credentials(user_id);

        CREATE TABLE webauthn_challenges (
            challenge_id TEXT PRIMARY KEY,
            user_id      TEXT,
            challenge    BYTEA NOT NULL,
            expires_at   BIGINT NOT NULL
        );
        """.trimIndent(),
    )
}

/** Applies all pending migrations to [connection] in a transaction. */
object Migrations {

    fun migrate(connection: Connection) {
        connection.autoCommit = false
        try {
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS schema_migrations (
                        version    INT PRIMARY KEY,
                        applied_at BIGINT NOT NULL
                    )
                    """.trimIndent()
                )
            }
            var applied = 0
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_migrations").use { rs ->
                    if (rs.next()) applied = rs.getInt(1)
                }
            }
            MigrationScripts.scripts.forEachIndexed { index, script ->
                val version = index + 1
                if (version > applied) {
                    connection.createStatement().use { statement ->
                        statement.execute(script)
                    }
                    connection.prepareStatement("INSERT INTO schema_migrations(version, applied_at) VALUES (?, ?)").use {
                        it.setInt(1, version)
                        it.setLong(2, System.currentTimeMillis())
                        it.executeUpdate()
                    }
                }
            }
            connection.commit()
        } catch (t: Throwable) {
            connection.rollback()
            throw IllegalStateException("Migration baze nije uspela: ${t.message}", t)
        } finally {
            connection.autoCommit = true
        }
    }
}
