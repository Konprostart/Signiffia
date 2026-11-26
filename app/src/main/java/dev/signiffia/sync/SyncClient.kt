package dev.signiffia.sync

/**
 * Typed client for the Signiffia sync server. The server is an untrusted relay: it only
 * ever receives ciphertext (the vault blob and wrapped Master Vault Key) and metadata. The
 * client never sends plaintext signing keys, keystore passwords, the Master Vault Key or the
 * recovery key over the wire.
 *
 * The wire protocol is the [WireCodec] binary format. All authenticated endpoints carry the
 * session token in the Authorization header (handled by [SyncTransport]); the account
 * authentication credential is completely separate from the vault Master Vault Key.
 */
class SyncClient(private val transport: SyncTransport) {

    // ------------------------------------------------------------- auth

    /** Fetches the account's Argon2id salt so a login can derive the verifier locally. */
    fun fetchSalt(username: String): ByteArray {
        val body = WireCodec.Writer().string(username).toBytes()
        val response = transport.request("GET", "/auth/salt", body)
        if (response.status != 200) throw error(response)
        return WireCodec.Reader(response.body).bytes()
    }

    fun register(username: String, salt: ByteArray, verifier: ByteArray) {
        val body = WireCodec.Writer().string(username).bytes(salt).bytes(verifier).toBytes()
        val response = transport.request("POST", "/auth/register", body)
        if (response.status != 200) throw error(response)
    }

    fun login(username: String, verifier: ByteArray): SessionInfo {
        val body = WireCodec.Writer().string(username).bytes(verifier).toBytes()
        val response = transport.request("POST", "/auth/login", body)
        if (response.status != 200) throw error(response)
        val reader = WireCodec.Reader(response.body)
        return SessionInfo(
            userId = reader.string(),
            sessionToken = reader.string(),
            deviceId = reader.string(),
        )
    }

    // ------------------------------------------------------------- vault

    /** Fetches the server-authoritative vault version + epoch before sealing an upload. */
    fun fetchVaultMeta(session: SessionInfo): VaultMeta {
        val response = transport.request("GET", "/vault/meta", ByteArray(0), session.sessionToken)
        if (response.status != 200) throw error(response)
        val reader = WireCodec.Reader(response.body)
        return VaultMeta(vaultVersion = reader.long(), epoch = reader.long())
    }

    fun uploadVault(session: SessionInfo, snapshot: VaultSnapshot) {
        val body = WireCodec.Writer()
            .long(snapshot.vaultVersion)
            .long(snapshot.epoch)
            .string(snapshot.deviceId)
            .long(snapshot.createdAt)
            .string(snapshot.recordsSha256)
            .bytes(snapshot.nonce)
            .bytes(snapshot.ciphertext)
            .bytes(snapshot.wrappedMvkRecovery)
            .toBytes()
        val response = transport.request("PUT", "/vault/object", body, session.sessionToken)
        if (response.status != 200) throw error(response)
    }

    fun downloadVault(session: SessionInfo): VaultSnapshot {
        val response = transport.request("GET", "/vault/object", ByteArray(0), session.sessionToken)
        if (response.status == 404) throw SyncException("Vault nije pronađen na serveru.", 404)
        if (response.status != 200) throw error(response)
        val reader = WireCodec.Reader(response.body)
        return VaultSnapshot(
            vaultVersion = reader.long(),
            epoch = reader.long(),
            deviceId = reader.string(),
            createdAt = reader.long(),
            recordsSha256 = reader.string(),
            nonce = reader.bytes(),
            ciphertext = reader.bytes(),
            wrappedMvkRecovery = reader.bytes(),
        )
    }

    // ------------------------------------------------------------- devices

    fun listDevices(session: SessionInfo): List<DeviceInfo> {
        val response = transport.request("GET", "/devices", ByteArray(0), session.sessionToken)
        if (response.status != 200) throw error(response)
        val reader = WireCodec.Reader(response.body)
        val count = reader.int()
        if (count < 0 || count > 10_000) throw SyncException("Neispravan broj uređaja u odgovoru.")
        return (0 until count).map {
            DeviceInfo(
                deviceId = reader.string(),
                name = reader.string(),
                platform = reader.string(),
                createdAt = reader.long(),
                lastSeenAt = reader.long(),
                revoked = reader.int() != 0,
            )
        }
    }

    /** Revokes a device; the server bumps the vault epoch. Returns the new epoch. */
    fun revokeDevice(session: SessionInfo, deviceId: String): Long {
        val body = WireCodec.Writer().string(deviceId).toBytes()
        val response = transport.request("POST", "/devices/revoke", body, session.sessionToken)
        if (response.status != 200) throw error(response)
        return WireCodec.Reader(response.body).long()
    }

    // ------------------------------------------------------------- enrollment

    /** Begins pairing: submits the new device's ECDH public key and gets a one-time ticket. */
    fun enrollBegin(session: SessionInfo, devicePublicKey: ByteArray): EnrollTicket {
        val body = WireCodec.Writer().bytes(devicePublicKey).toBytes()
        val response = transport.request("POST", "/enroll/begin", body, session.sessionToken)
        if (response.status != 200) throw error(response)
        val reader = WireCodec.Reader(response.body)
        return EnrollTicket(
            ticketId = reader.string(),
            expiresAtMillis = reader.long(),
            nonce = reader.bytes(),
        )
    }

    /** Completes pairing by handing the server the MVK wrapped for the new device. */
    fun enrollConfirm(session: SessionInfo, ticketId: String, wrappedMvkForDevice: ByteArray) {
        val body = WireCodec.Writer().string(ticketId).bytes(wrappedMvkForDevice).toBytes()
        val response = transport.request("POST", "/enroll/confirm", body, session.sessionToken)
        if (response.status != 200) throw error(response)
    }

    // ------------------------------------------------------------- webauthn / passkey
    // These endpoints exchange JSON (WebAuthn ceremony payloads) over the same transport. A
    // passkey is an authentication credential only — it never decrypts the vault.

    /** Starts passkey registration for the authenticated session; returns options JSON. */
    fun webauthnRegisterBegin(session: SessionInfo): String {
        val response = transport.request("POST", "/webauthn/register/begin", ByteArray(0), session.sessionToken)
        if (response.status != 200) throw error(response)
        return String(response.body, Charsets.UTF_8)
    }

    fun webauthnRegisterComplete(session: SessionInfo, responseJson: String) {
        val body = responseJson.toByteArray(Charsets.UTF_8)
        val response = transport.request("POST", "/webauthn/register/complete", body, session.sessionToken)
        if (response.status != 200) throw error(response)
    }

    /** Starts passkey authentication for a username; returns options JSON, or null when none. */
    fun webauthnAuthBegin(username: String): String? {
        val body = username.toByteArray(Charsets.UTF_8)
        val response = transport.request("POST", "/webauthn/auth/begin", body)
        if (response.status == 404) return null
        if (response.status != 200) throw error(response)
        return String(response.body, Charsets.UTF_8)
    }

    /** Completes passkey authentication; returns a session on success or null. */
    fun webauthnAuthComplete(responseJson: String): SessionInfo? {
        val body = responseJson.toByteArray(Charsets.UTF_8)
        val response = transport.request("POST", "/webauthn/auth/complete", body)
        if (response.status != 200) return null
        val json = String(response.body, Charsets.UTF_8)
        return SessionInfo(
            userId = jsonField(json, "userId"),
            sessionToken = jsonField(json, "sessionToken"),
            deviceId = jsonField(json, "deviceId"),
        )
    }

    private fun jsonField(json: String, field: String): String {
        val key = "\"$field\":\""
        val start = json.indexOf(key)
        if (start < 0) throw SyncException("Neispravan JSON odgovor servera.")
        val valueStart = start + key.length
        val valueEnd = json.indexOf('"', valueStart)
        if (valueEnd < 0) throw SyncException("Neispravan JSON odgovor servera.")
        return json.substring(valueStart, valueEnd)
    }

    private fun error(response: HttpResponse): SyncException {
        val message = runCatching { String(response.body, Charsets.UTF_8) }
            .getOrDefault("HTTP ${response.status}")
            .takeIf { it.isNotBlank() } ?: "HTTP ${response.status}"
        return SyncException(message, response.status)
    }
}
