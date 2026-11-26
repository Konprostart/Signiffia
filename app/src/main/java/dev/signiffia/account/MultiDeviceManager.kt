package dev.signiffia.account

import android.util.Base64
import dev.signiffia.data.KeyRepository
import dev.signiffia.sync.DeviceInfo
import dev.signiffia.sync.HttpSyncTransport
import dev.signiffia.sync.SessionInfo
import dev.signiffia.sync.SyncClient
import dev.signiffia.sync.SyncTransport
import dev.signiffia.sync.VaultSnapshot
import dev.signiffia.vault.RecoveryKey
import dev.signiffia.vault.SealedBox
import dev.signiffia.vault.VaultCrypto
import dev.signiffia.vault.VaultManifest
import dev.signiffia.vault.VaultObject
import dev.signiffia.vault.VaultService

/**
 * Orchestrates the multi-device account, encrypted vault and sync flows for the UI.
 *
 * Security invariants this class enforces:
 *  - The Master Vault Key never touches disk in plaintext — it is stored wrapped with the
 *    Android Keystore device key (and, separately, wrapped with the recovery key for upload).
 *  - Only ciphertext leaves the device: the vault blob is AES-256-GCM under the MVK, and the
 *    recovery-wrapped MVK is itself ciphertext.
 *  - Signing keys are never regenerated: sync uploads the existing `KeyRepository` entries and
 *    restore reuses the existing fingerprint-validating `KeyRepository.restore`.
 *  - A successful account login never grants the vault: the MVK must additionally be unlocked
 *    by the device key (user auth) or the recovery key.
 */
class MultiDeviceManager(
    private val repository: KeyRepository,
    private val deviceKey: DeviceKey,
    private val session: AccountSession,
    private val passkeyClient: PasskeyClient,
    private val transportFactory: (String) -> SyncTransport = { HttpSyncTransport(it) },
) {

    fun loadState(): AccountState = session.load()

    // ------------------------------------------------------------- account

    suspend fun register(username: String, password: CharArray, serverUrl: String, deviceName: String): AccountState {
        val client = SyncClient(transportFactory(serverUrl))
        val salt = PasswordKdf.generateSalt()
        val verifier = PasswordKdf.hash(password, salt)
        client.register(username, salt, verifier)
        val info = client.login(username, verifier)
        return persistSession(info, serverUrl, deviceName, base64(salt)).also { session.save(it) }
    }

    suspend fun login(username: String, password: CharArray, serverUrl: String, deviceName: String): AccountState {
        val client = SyncClient(transportFactory(serverUrl))
        val salt = client.fetchSalt(username)
        val verifier = PasswordKdf.hash(password, salt)
        val info = client.login(username, verifier)
        val previous = session.load()
        val state = AccountState(
            userId = info.userId,
            sessionToken = info.sessionToken,
            deviceId = info.deviceId,
            deviceName = deviceName.ifBlank { previous.deviceName },
            serverUrl = serverUrl,
            accountSalt = base64(salt),
            // Preserve any locally-held master key across a re-login on the same device.
            wrappedMvkNonce = previous.wrappedMvkNonce,
            wrappedMvkCiphertext = previous.wrappedMvkCiphertext,
            wrappedMvkRecoveryNonce = previous.wrappedMvkRecoveryNonce,
            wrappedMvkRecoveryCiphertext = previous.wrappedMvkRecoveryCiphertext,
            vaultVersion = previous.vaultVersion,
            epoch = previous.epoch,
        )
        session.save(state)
        return state
    }

    fun logout() = session.clear()

    // ------------------------------------------------------------- passkey

    /**
     * Sign-in with a passkey. Returns the new [AccountState] on success, or null when the
     * account has no passkey or the ceremony fails (the caller then falls back to password).
     * A passkey login issues a session token but never decrypts the vault — that still needs
     * the recovery key or the device key.
     */
    suspend fun passkeyLogin(username: String, serverUrl: String, deviceName: String): AccountState? {
        val client = SyncClient(transportFactory(serverUrl))
        val options = client.webauthnAuthBegin(username) ?: return null
        val responseJson = passkeyClient.getPasskey(options)
        val info = client.webauthnAuthComplete(responseJson) ?: return null
        val previous = session.load()
        val state = AccountState(
            userId = info.userId,
            sessionToken = info.sessionToken,
            deviceId = info.deviceId,
            deviceName = deviceName.ifBlank { previous.deviceName },
            serverUrl = serverUrl,
            accountSalt = previous.accountSalt,
            wrappedMvkNonce = previous.wrappedMvkNonce,
            wrappedMvkCiphertext = previous.wrappedMvkCiphertext,
            wrappedMvkRecoveryNonce = previous.wrappedMvkRecoveryNonce,
            wrappedMvkRecoveryCiphertext = previous.wrappedMvkRecoveryCiphertext,
            vaultVersion = previous.vaultVersion,
            epoch = previous.epoch,
        )
        session.save(state)
        return state
    }

    /** Registers a passkey for the currently authenticated account. */
    suspend fun registerPasskey() {
        val state = session.load()
        require(state.isLoggedIn) { "Najpre se prijavi na nalog." }
        val client = SyncClient(transportFactory(state.serverUrl))
        val options = client.webauthnRegisterBegin(toSessionInfo(state))
        val responseJson = passkeyClient.createPasskey(options)
        client.webauthnRegisterComplete(toSessionInfo(state), responseJson)
    }

    // ------------------------------------------------------------- recovery

    /**
     * First device only: creates the Master Vault Key, the recovery mnemonic, and the two
     * wrapped copies (device key + recovery key). Returns the mnemonic for a one-time display.
     */
    suspend fun setupRecovery(): String {
        val state = session.load()
        if (state.hasRecoveryWrap) throw IllegalStateException("Recovery ključ je već postavljen.")
        val mvk = VaultCrypto.generateKey()
        val mnemonic = RecoveryKey.generate()
        val recoveryKey = RecoveryKey.wrappingKey(mnemonic)
        val wrappedMvkRecovery = VaultService.wrapMvkForRecovery(mvk, recoveryKey)
        val wrappedMvkLocal = deviceKey.wrapMvk(mvk)
        val (localNonce, localCt) = boxToB64(wrappedMvkLocal)
        val (recNonce, recCt) = boxToB64(wrappedMvkRecovery)
        session.save(
            state.copy(
                wrappedMvkNonce = localNonce,
                wrappedMvkCiphertext = localCt,
                wrappedMvkRecoveryNonce = recNonce,
                wrappedMvkRecoveryCiphertext = recCt,
            )
        )
        return mnemonic
    }

    /**
     * Recovers the vault after losing every device: logs in with the account and unlocks the
     * Master Vault Key with only the recovery mnemonic, then restores the keys.
     */
    suspend fun recoverWithRecoveryKey(mnemonic: String) {
        val state = session.load()
        require(state.isLoggedIn) { "Najpre se prijavi na nalog." }
        val client = SyncClient(transportFactory(state.serverUrl))
        val snapshot = client.downloadVault(toSessionInfo(state))
        val recoveryKey = RecoveryKey.wrappingKey(mnemonic)
        val mvk = VaultService.unwrapMvkFromRecovery(SealedBox.fromBytes(snapshot.wrappedMvkRecovery), recoveryKey)
        val entries = unseal(snapshot, mvk)
        repository.restore(entries)
        // Store the MVK wrapped with this device's key for future syncs.
        val (nonce, ct) = boxToB64(deviceKey.wrapMvk(mvk))
        session.save(
            state.copy(
                wrappedMvkNonce = nonce,
                wrappedMvkCiphertext = ct,
                vaultVersion = snapshot.vaultVersion,
                epoch = snapshot.epoch,
            )
        )
    }

    // ------------------------------------------------------------- sync

    /**
     * Uploads the current keys to the server as an encrypted vault object.
     *
     * The server is authoritative for both the vault version and the revocation epoch, so the
     * client fetches them first and seals with `version+1` at the *current* epoch. This
     * prevents a stale-epoch upload (which the server would reject) and guarantees the AAD
     * bound into the ciphertext always matches the version/epoch the server records.
     */
    suspend fun syncNow() {
        val state = session.load()
        require(state.isLoggedIn) { "Najpre se prijavi na nalog." }
        val client = SyncClient(transportFactory(state.serverUrl))
        val meta = client.fetchVaultMeta(toSessionInfo(state))
        val mvk = deviceKey.unwrapMvk(state.wrappedMvk() ?: throw IllegalStateException("Nema ključa trezora."))
        val entries = repository.backupEntries()
        val vaultVersion = meta.vaultVersion + 1
        val sealed = VaultService.seal(entries, mvk, state.deviceId, vaultVersion, meta.epoch)
        val snapshot = VaultSnapshot(
            vaultVersion = sealed.manifest.vaultVersion,
            epoch = sealed.manifest.epoch,
            deviceId = sealed.manifest.deviceId,
            createdAt = sealed.manifest.createdAt,
            recordsSha256 = sealed.manifest.recordsSha256,
            nonce = sealed.box.nonce,
            ciphertext = sealed.box.ciphertext,
            wrappedMvkRecovery = state.wrappedMvkRecovery()?.toBytes()
                ?: throw IllegalStateException("Nema recovery ključa; postavi ga pre sinhronizacije."),
        )
        client.uploadVault(toSessionInfo(state), snapshot)
        session.save(state.copy(vaultVersion = vaultVersion, epoch = meta.epoch))
    }

    /** Downloads the vault and restores the keys (new device with an already-unwrapped MVK). */
    suspend fun downloadAndRestore() {
        val state = session.load()
        require(state.isLoggedIn) { "Najpre se prijavi na nalog." }
        val mvk = deviceKey.unwrapMvk(state.wrappedMvk() ?: throw IllegalStateException("Nema ključa trezora."))
        val snapshot = SyncClient(transportFactory(state.serverUrl)).downloadVault(toSessionInfo(state))
        val entries = unseal(snapshot, mvk)
        repository.restore(entries)
        session.save(state.copy(vaultVersion = snapshot.vaultVersion, epoch = snapshot.epoch))
    }

    // ------------------------------------------------------------- devices

    suspend fun listDevices(): List<DeviceInfo> {
        val state = session.load()
        require(state.isLoggedIn) { "Najpre se prijavi na nalog." }
        return SyncClient(transportFactory(state.serverUrl)).listDevices(toSessionInfo(state))
    }

    suspend fun revokeDevice(deviceId: String): Long {
        val state = session.load()
        require(state.isLoggedIn) { "Najpre se prijavi na nalog." }
        val newEpoch = SyncClient(transportFactory(state.serverUrl)).revokeDevice(toSessionInfo(state), deviceId)
        session.save(state.copy(epoch = newEpoch))
        return newEpoch
    }

    // ------------------------------------------------------------- helpers

    private fun unseal(snapshot: VaultSnapshot, mvk: ByteArray): List<dev.signiffia.core.BackupEntry> {
        val manifest = VaultManifest(
            vaultVersion = snapshot.vaultVersion,
            epoch = snapshot.epoch,
            deviceId = snapshot.deviceId,
            createdAt = snapshot.createdAt,
            recordsSha256 = snapshot.recordsSha256,
        )
        return VaultService.unseal(VaultObject(manifest, SealedBox(snapshot.nonce, snapshot.ciphertext)), mvk)
    }

    private fun toSessionInfo(state: AccountState) = SessionInfo(state.userId, state.sessionToken, state.deviceId)

    private fun persistSession(info: SessionInfo, serverUrl: String, deviceName: String, salt: String) = AccountState(
        userId = info.userId,
        sessionToken = info.sessionToken,
        deviceId = info.deviceId,
        deviceName = deviceName,
        serverUrl = serverUrl,
        accountSalt = salt,
    )

    private fun boxToB64(box: SealedBox): Pair<String, String> =
        Base64.encodeToString(box.nonce, Base64.NO_WRAP) to Base64.encodeToString(box.ciphertext, Base64.NO_WRAP)

    private fun base64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
}
