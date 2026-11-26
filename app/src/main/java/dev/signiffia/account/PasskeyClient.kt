package dev.signiffia.account

import android.content.Context
import android.os.Build
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential

/**
 * Abstraction for the WebAuthn/passkey *client*. The server is the relying party: it returns
 * `PublicKeyCredentialCreationOptions` / `PublicKeyCredentialRequestOptions` JSON (which already
 * carries the `rp.id`), the client asks the platform authenticator (Android Keystore / Play
 * Services FIDO2) to satisfy them, and returns the response JSON to the server for verification.
 *
 * A passkey is an *authentication* credential only — it never decrypts the vault, and it never
 * replaces the recovery key. After a successful passkey login the server issues the same kind
 * of session token as password login; the Master Vault Key still needs the recovery key or the
 * device key.
 */
interface PasskeyClient {
    val isSupported: Boolean

    /** Runs a passkey creation ceremony; returns the registration response JSON. */
    suspend fun createPasskey(optionsJson: String): String

    /** Runs a passkey assertion ceremony; returns the authentication response JSON. */
    suspend fun getPasskey(optionsJson: String): String
}

/**
 * [PasskeyClient] backed by `androidx.credentials`. Full passkey UX needs Android 14+
 * (API 34) and a Google Play Services credential provider; on older devices or without a
 * provider the ceremony fails and the caller falls back to password login.
 */
class AndroidPasskeyClient(context: Context) : PasskeyClient {

    private val appContext: Context = context.applicationContext
    private val credentialManager = CredentialManager.create(appContext)

    override val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    override suspend fun createPasskey(optionsJson: String): String {
        val request = CreatePublicKeyCredentialRequest(optionsJson)
        val result = credentialManager.createCredential(appContext, request)
        return (result as CreatePublicKeyCredentialResponse).registrationResponseJson
    }

    override suspend fun getPasskey(optionsJson: String): String {
        val option = GetPublicKeyCredentialOption(optionsJson, null)
        val request = GetCredentialRequest(listOf(option))
        val result = credentialManager.getCredential(appContext, request)
        return (result.credential as PublicKeyCredential).authenticationResponseJson
    }
}
