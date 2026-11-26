package dev.signiffia.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import javax.sql.DataSource

/**
 * Persistent [Store] backed by PostgreSQL via a HikariCP connection pool.
 *
 * Construction applies pending schema migrations, so a fresh or upgraded database is brought
 * to the current schema automatically and non-destructively. All secrets remain ciphertext:
 * the verifier is an Argon2id hash and every vault field is already encrypted by the client.
 *
 * Configuration is read from the environment (see README):
 *  - SIGNIFFIA_DB_URL (default jdbc:postgresql://localhost:5432/signiffia)
 *  - SIGNIFFIA_DB_USER
 *  - SIGNIFFIA_DB_PASSWORD
 */
class PostgresStore private constructor(
    private val dataSource: DataSource,
) : Store {

    private inline fun <T> withConnection(block: (Connection) -> T): T =
        dataSource.connection.use { block(it) }

    // ------------------------------------------------------------- users

    override fun findUserByName(username: String): UserRecord? = withConnection { conn ->
        conn.prepareStatement("SELECT user_id, username, verifier, salt FROM users WHERE username = ?").use { st ->
            st.setString(1, username)
            st.executeQuery().use { rs ->
                if (rs.next()) {
                    UserRecord(rs.getString(1), rs.getString(2), rs.getBytes(3), rs.getBytes(4))
                } else null
            }
        }
    }

    override fun findUserById(userId: String): UserRecord? = withConnection { conn ->
        conn.prepareStatement("SELECT user_id, username, verifier, salt FROM users WHERE user_id = ?").use { st ->
            st.setString(1, userId)
            st.executeQuery().use { rs ->
                if (rs.next()) {
                    UserRecord(rs.getString(1), rs.getString(2), rs.getBytes(3), rs.getBytes(4))
                } else null
            }
        }
    }

    override fun insertUser(user: UserRecord): Boolean = withConnection { conn ->
        conn.prepareStatement(
            "INSERT INTO users (user_id, username, verifier, salt, created_at) VALUES (?, ?, ?, ?, ?) " +
                "ON CONFLICT (username) DO NOTHING"
        ).use { st ->
            st.setString(1, user.userId)
            st.setString(2, user.username)
            st.setBytes(3, user.verifier)
            st.setBytes(4, user.salt)
            st.setLong(5, System.currentTimeMillis())
            st.executeUpdate() > 0
        }
    }

    // ------------------------------------------------------------- sessions

    override fun insertSession(tokenHash: String, session: SessionRecord) {
        withConnection { conn ->
            conn.prepareStatement(
                "INSERT INTO sessions (token_hash, user_id, device_id, created_at, expires_at) VALUES (?, ?, ?, ?, ?)"
            ).use { st ->
                st.setString(1, tokenHash)
                st.setString(2, session.userId)
                st.setString(3, session.deviceId)
                st.setLong(4, session.createdAt)
                st.setLong(5, session.expiresAt)
                st.executeUpdate()
            }
        }
    }

    override fun findSession(tokenHash: String): SessionRecord? = withConnection { conn ->
        conn.prepareStatement("SELECT user_id, device_id, created_at, expires_at FROM sessions WHERE token_hash = ?").use { st ->
            st.setString(1, tokenHash)
            st.executeQuery().use { rs ->
                if (rs.next()) {
                    SessionRecord(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4))
                } else null
            }
        }
    }

    override fun deleteSession(tokenHash: String) {
        withConnection { conn ->
            conn.prepareStatement("DELETE FROM sessions WHERE token_hash = ?").use { st ->
                st.setString(1, tokenHash)
                st.executeUpdate()
            }
        }
    }

    // ------------------------------------------------------------- devices

    override fun insertDevice(device: DeviceRecord) {
        withConnection { conn ->
            conn.prepareStatement(
                "INSERT INTO devices (device_id, user_id, name, platform, created_at, last_seen_at, revoked) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (device_id) DO NOTHING"
            ).use { st ->
                st.setString(1, device.deviceId)
                st.setString(2, device.userId)
                st.setString(3, device.name)
                st.setString(4, device.platform)
                st.setLong(5, device.createdAt)
                st.setLong(6, device.lastSeenAt)
                st.setBoolean(7, device.revoked)
                st.executeUpdate()
            }
        }
    }

    override fun findDevice(deviceId: String): DeviceRecord? = withConnection { conn ->
        conn.prepareStatement(
            "SELECT device_id, user_id, name, platform, created_at, last_seen_at, revoked FROM devices WHERE device_id = ?"
        ).use { st ->
            st.setString(1, deviceId)
            st.executeQuery().use { rs ->
                if (rs.next()) {
                    DeviceRecord(
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getLong(5), rs.getLong(6), rs.getBoolean(7),
                    )
                } else null
            }
        }
    }

    override fun touchDevice(deviceId: String, lastSeenAt: Long) {
        withConnection { conn ->
            conn.prepareStatement("UPDATE devices SET last_seen_at = ? WHERE device_id = ?").use { st ->
                st.setLong(1, lastSeenAt)
                st.setString(2, deviceId)
                st.executeUpdate()
            }
        }
    }

    override fun setDeviceRevoked(deviceId: String, revoked: Boolean) {
        withConnection { conn ->
            conn.prepareStatement("UPDATE devices SET revoked = ? WHERE device_id = ?").use { st ->
                st.setBoolean(1, revoked)
                st.setString(2, deviceId)
                st.executeUpdate()
            }
        }
    }

    override fun listDevices(userId: String): List<DeviceRecord> = withConnection { conn ->
        conn.prepareStatement(
            "SELECT device_id, user_id, name, platform, created_at, last_seen_at, revoked FROM devices " +
                "WHERE user_id = ? ORDER BY created_at"
        ).use { st ->
            st.setString(1, userId)
            st.executeQuery().use { rs ->
                val out = ArrayList<DeviceRecord>()
                while (rs.next()) {
                    out += DeviceRecord(
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getLong(5), rs.getLong(6), rs.getBoolean(7),
                    )
                }
                out
            }
        }
    }

    // ------------------------------------------------------------- vault

    override fun getVault(userId: String): VaultRecord? = withConnection { conn ->
        conn.prepareStatement(
            "SELECT vault_version, epoch, device_id, created_at, records_sha256, nonce, ciphertext, wrapped_mvk_recovery " +
                "FROM vault_objects WHERE user_id = ?"
        ).use { st ->
            st.setString(1, userId)
            st.executeQuery().use { rs ->
                if (rs.next()) {
                    VaultRecord(
                        rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4),
                        rs.getString(5), rs.getBytes(6), rs.getBytes(7), rs.getBytes(8),
                    )
                } else null
            }
        }
    }

    override fun putVault(userId: String, vault: VaultRecord) {
        withConnection { conn ->
            conn.prepareStatement(
                "INSERT INTO vault_objects (user_id, vault_version, epoch, device_id, created_at, records_sha256, nonce, ciphertext, wrapped_mvk_recovery) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT (user_id) DO UPDATE SET vault_version = EXCLUDED.vault_version, epoch = EXCLUDED.epoch, " +
                    "device_id = EXCLUDED.device_id, created_at = EXCLUDED.created_at, records_sha256 = EXCLUDED.records_sha256, " +
                    "nonce = EXCLUDED.nonce, ciphertext = EXCLUDED.ciphertext, wrapped_mvk_recovery = EXCLUDED.wrapped_mvk_recovery"
            ).use { st ->
                st.setString(1, userId)
                st.setLong(2, vault.vaultVersion)
                st.setLong(3, vault.epoch)
                st.setString(4, vault.deviceId)
                st.setLong(5, vault.createdAt)
                st.setString(6, vault.recordsSha256)
                st.setBytes(7, vault.nonce)
                st.setBytes(8, vault.ciphertext)
                st.setBytes(9, vault.wrappedMvkRecovery)
                st.executeUpdate()
            }
        }
    }

    // ------------------------------------------------------------- epoch

    override fun getEpoch(userId: String): Long = withConnection { conn ->
        conn.prepareStatement("SELECT epoch FROM user_epochs WHERE user_id = ?").use { st ->
            st.setString(1, userId)
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 1L }
        }
    }

    override fun setEpoch(userId: String, epoch: Long) {
        withConnection { conn ->
            conn.prepareStatement(
                "INSERT INTO user_epochs (user_id, epoch) VALUES (?, ?) " +
                    "ON CONFLICT (user_id) DO UPDATE SET epoch = EXCLUDED.epoch"
            ).use { st ->
                st.setString(1, userId)
                st.setLong(2, epoch)
                st.executeUpdate()
            }
        }
    }

    // ------------------------------------------------------------- enrollment

    override fun insertTicket(ticket: EnrollTicketRecord) {
        withConnection { conn ->
            conn.prepareStatement(
                "INSERT INTO enroll_tokens (ticket_id, user_id, device_public_key, nonce, expires_at, used) VALUES (?, ?, ?, ?, ?, ?)"
            ).use { st ->
                st.setString(1, ticket.ticketId)
                st.setString(2, ticket.userId)
                st.setBytes(3, ticket.devicePublicKey)
                st.setBytes(4, ticket.nonce)
                st.setLong(5, ticket.expiresAt)
                st.setBoolean(6, ticket.used)
                st.executeUpdate()
            }
        }
    }

    override fun findTicket(ticketId: String): EnrollTicketRecord? = withConnection { conn ->
        conn.prepareStatement(
            "SELECT ticket_id, user_id, device_public_key, nonce, expires_at, used FROM enroll_tokens WHERE ticket_id = ?"
        ).use { st ->
            st.setString(1, ticketId)
            st.executeQuery().use { rs ->
                if (rs.next()) {
                    EnrollTicketRecord(
                        rs.getString(1), rs.getString(2), rs.getBytes(3), rs.getBytes(4),
                        rs.getLong(5), rs.getBoolean(6),
                    )
                } else null
            }
        }
    }

    override fun markTicketUsed(ticketId: String) {
        withConnection { conn ->
            conn.prepareStatement("UPDATE enroll_tokens SET used = TRUE WHERE ticket_id = ?").use { st ->
                st.setString(1, ticketId)
                st.executeUpdate()
            }
        }
    }

    // ------------------------------------------------------------- webauthn / passkey

    override fun insertWebAuthnCredential(credential: WebAuthnCredentialRecord) {
        withConnection { conn ->
            conn.prepareStatement(
                "INSERT INTO webauthn_credentials (credential_id, user_id, record_json, sign_count, created_at) " +
                    "VALUES (?, ?, ?, ?, ?) ON CONFLICT (credential_id) DO UPDATE SET record_json = EXCLUDED.record_json"
            ).use { st ->
                st.setString(1, credential.credentialId)
                st.setString(2, credential.userId)
                st.setString(3, credential.recordJson)
                st.setLong(4, credential.signCount)
                st.setLong(5, credential.createdAt)
                st.executeUpdate()
            }
        }
    }

    override fun findWebAuthnCredential(credentialId: String): WebAuthnCredentialRecord? = withConnection { conn ->
        conn.prepareStatement(
            "SELECT credential_id, user_id, record_json, sign_count, created_at FROM webauthn_credentials WHERE credential_id = ?"
        ).use { st ->
            st.setString(1, credentialId)
            st.executeQuery().use { rs ->
                if (rs.next()) {
                    WebAuthnCredentialRecord(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getLong(5))
                } else null
            }
        }
    }

    override fun listWebAuthnCredentials(userId: String): List<WebAuthnCredentialRecord> = withConnection { conn ->
        conn.prepareStatement(
            "SELECT credential_id, user_id, record_json, sign_count, created_at FROM webauthn_credentials WHERE user_id = ?"
        ).use { st ->
            st.setString(1, userId)
            st.executeQuery().use { rs ->
                val out = ArrayList<WebAuthnCredentialRecord>()
                while (rs.next()) {
                    out += WebAuthnCredentialRecord(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getLong(5))
                }
                out
            }
        }
    }

    override fun updateWebAuthnSignCount(credentialId: String, signCount: Long) {
        withConnection { conn ->
            conn.prepareStatement("UPDATE webauthn_credentials SET sign_count = ? WHERE credential_id = ?").use { st ->
                st.setLong(1, signCount)
                st.setString(2, credentialId)
                st.executeUpdate()
            }
        }
    }

    override fun insertWebAuthnChallenge(challenge: WebAuthnChallengeRecord) {
        withConnection { conn ->
            conn.prepareStatement(
                "INSERT INTO webauthn_challenges (challenge_id, user_id, challenge, expires_at) VALUES (?, ?, ?, ?)"
            ).use { st ->
                st.setString(1, challenge.challengeId)
                st.setString(2, challenge.userId)
                st.setBytes(3, challenge.challenge)
                st.setLong(4, challenge.expiresAt)
                st.executeUpdate()
            }
        }
    }

    override fun findWebAuthnChallenge(challengeId: String): WebAuthnChallengeRecord? = withConnection { conn ->
        conn.prepareStatement(
            "SELECT challenge_id, user_id, challenge, expires_at FROM webauthn_challenges WHERE challenge_id = ?"
        ).use { st ->
            st.setString(1, challengeId)
            st.executeQuery().use { rs ->
                if (rs.next()) {
                    WebAuthnChallengeRecord(rs.getString(1), rs.getString(2), rs.getBytes(3), rs.getLong(4))
                } else null
            }
        }
    }

    override fun deleteWebAuthnChallenge(challengeId: String) {
        withConnection { conn ->
            conn.prepareStatement("DELETE FROM webauthn_challenges WHERE challenge_id = ?").use { st ->
                st.setString(1, challengeId)
                st.executeUpdate()
            }
        }
    }

    override fun check() {
        withConnection { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT 1").use { rs -> rs.next() }
            }
        }
    }

    companion object {
        fun create(
            databaseUrl: String = System.getenv("SIGNIFFIA_DB_URL")
                ?: "jdbc:postgresql://localhost:5432/signiffia",
            dbUser: String = System.getenv("SIGNIFFIA_DB_USER") ?: "signiffia",
            dbPassword: String = System.getenv("SIGNIFFIA_DB_PASSWORD") ?: "signiffia",
        ): PostgresStore {
            val config = HikariConfig().apply {
                jdbcUrl = databaseUrl
                username = dbUser
                password = dbPassword
                maximumPoolSize = 10
                minimumIdle = 1
                connectionTimeout = 10_000
                isAutoCommit = true
                poolName = "signiffia-server"
                // Never log SQL or sensitive parameters.
                addDataSourceProperty("logUnclosedConnections", "false")
            }
            val dataSource = HikariDataSource(config)
            dataSource.connection.use { Migrations.migrate(it) }
            return PostgresStore(dataSource)
        }
    }
}
