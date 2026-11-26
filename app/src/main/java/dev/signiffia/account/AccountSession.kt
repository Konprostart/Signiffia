package dev.signiffia.account

import android.content.Context
import android.util.Base64
import dev.signiffia.vault.SealedBox

/** A persisted Signiffia account/session state (device-local, app-private storage). */
data class AccountState(
    val userId: String = "",
    val sessionToken: String = "",
    val deviceId: String = "",
    val deviceName: String = "",
    val serverUrl: String = "",
    val accountSalt: String = "", // base64 Argon2id salt for the account verifier
    val wrappedMvkNonce: String = "",
    val wrappedMvkCiphertext: String = "",
    val wrappedMvkRecoveryNonce: String = "",
    val wrappedMvkRecoveryCiphertext: String = "",
    val vaultVersion: Long = 0,
    val epoch: Long = 1,
) {
    val isLoggedIn: Boolean get() = sessionToken.isNotBlank()
    val hasMasterKey: Boolean get() = wrappedMvkNonce.isNotBlank() && wrappedMvkCiphertext.isNotBlank()
    val hasRecoveryWrap: Boolean get() =
        wrappedMvkRecoveryNonce.isNotBlank() && wrappedMvkRecoveryCiphertext.isNotBlank()

    /** The MVK wrapped with this device's keystore key (ciphertext only). */
    fun wrappedMvk(): SealedBox? {
        if (!hasMasterKey) return null
        return SealedBox(
            nonce = Base64.decode(wrappedMvkNonce, Base64.NO_WRAP),
            ciphertext = Base64.decode(wrappedMvkCiphertext, Base64.NO_WRAP),
        )
    }

    /** The MVK wrapped with the recovery key, uploaded to the server for recovery. */
    fun wrappedMvkRecovery(): SealedBox? {
        if (!hasRecoveryWrap) return null
        return SealedBox(
            nonce = Base64.decode(wrappedMvkRecoveryNonce, Base64.NO_WRAP),
            ciphertext = Base64.decode(wrappedMvkRecoveryCiphertext, Base64.NO_WRAP),
        )
    }
}

/**
 * Persists [AccountState] in [android.content.SharedPreferences] (app-private, sandboxed).
 *
 * The session token is a bearer credential for vault download; the vault itself is still
 * encrypted with the Master Vault Key, which is stored here only in wrapped (encrypted)
 * form under the Android Keystore device key, so the token alone cannot decrypt the vault.
 */
class AccountSession(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): AccountState = AccountState(
        userId = prefs.getString(KEY_USER_ID, "") ?: "",
        sessionToken = prefs.getString(KEY_SESSION, "") ?: "",
        deviceId = prefs.getString(KEY_DEVICE_ID, "") ?: "",
        deviceName = prefs.getString(KEY_DEVICE_NAME, "") ?: "",
        serverUrl = prefs.getString(KEY_SERVER, "") ?: "",
        accountSalt = prefs.getString(KEY_SALT, "") ?: "",
        wrappedMvkNonce = prefs.getString(KEY_MVK_NONCE, "") ?: "",
        wrappedMvkCiphertext = prefs.getString(KEY_MVK_CT, "") ?: "",
        wrappedMvkRecoveryNonce = prefs.getString(KEY_MVK_REC_NONCE, "") ?: "",
        wrappedMvkRecoveryCiphertext = prefs.getString(KEY_MVK_REC_CT, "") ?: "",
        vaultVersion = prefs.getLong(KEY_VAULT_VERSION, 0L),
        epoch = prefs.getLong(KEY_EPOCH, 1L),
    )

    fun save(state: AccountState) {
        prefs.edit()
            .putString(KEY_USER_ID, state.userId)
            .putString(KEY_SESSION, state.sessionToken)
            .putString(KEY_DEVICE_ID, state.deviceId)
            .putString(KEY_DEVICE_NAME, state.deviceName)
            .putString(KEY_SERVER, state.serverUrl)
            .putString(KEY_SALT, state.accountSalt)
            .putString(KEY_MVK_NONCE, state.wrappedMvkNonce)
            .putString(KEY_MVK_CT, state.wrappedMvkCiphertext)
            .putString(KEY_MVK_REC_NONCE, state.wrappedMvkRecoveryNonce)
            .putString(KEY_MVK_REC_CT, state.wrappedMvkRecoveryCiphertext)
            .putLong(KEY_VAULT_VERSION, state.vaultVersion)
            .putLong(KEY_EPOCH, state.epoch)
            .apply()
    }

    /** Keeps the account but forgets the wrapped MVK (e.g. after a manual log out). */
    fun clearMasterKey(state: AccountState) {
        save(
            state.copy(
                wrappedMvkNonce = "",
                wrappedMvkCiphertext = "",
                wrappedMvkRecoveryNonce = "",
                wrappedMvkRecoveryCiphertext = "",
            )
        )
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS_NAME = "signiffia_account"
        const val KEY_USER_ID = "user_id"
        const val KEY_SESSION = "session"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_DEVICE_NAME = "device_name"
        const val KEY_SERVER = "server_url"
        const val KEY_SALT = "account_salt"
        const val KEY_MVK_NONCE = "mvk_nonce"
        const val KEY_MVK_CT = "mvk_ciphertext"
        const val KEY_MVK_REC_NONCE = "mvk_rec_nonce"
        const val KEY_MVK_REC_CT = "mvk_rec_ciphertext"
        const val KEY_VAULT_VERSION = "vault_version"
        const val KEY_EPOCH = "epoch"
    }
}
