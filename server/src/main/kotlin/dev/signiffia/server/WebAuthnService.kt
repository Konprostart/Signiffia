package dev.signiffia.server

import com.webauthn4j.WebAuthnManager
import com.webauthn4j.converter.util.ObjectConverter
import com.webauthn4j.credential.CredentialRecordImpl
import com.webauthn4j.data.AttestationConveyancePreference
import com.webauthn4j.data.AuthenticationParameters
import com.webauthn4j.data.AuthenticatorSelectionCriteria
import com.webauthn4j.data.AuthenticatorTransport
import com.webauthn4j.data.PublicKeyCredentialCreationOptions
import com.webauthn4j.data.PublicKeyCredentialDescriptor
import com.webauthn4j.data.PublicKeyCredentialParameters
import com.webauthn4j.data.PublicKeyCredentialRequestOptions
import com.webauthn4j.data.PublicKeyCredentialRpEntity
import com.webauthn4j.data.PublicKeyCredentialType
import com.webauthn4j.data.PublicKeyCredentialUserEntity
import com.webauthn4j.data.RegistrationParameters
import com.webauthn4j.data.ResidentKeyRequirement
import com.webauthn4j.data.UserVerificationRequirement
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier
import com.webauthn4j.data.client.Origin
import com.webauthn4j.data.client.challenge.DefaultChallenge
import com.webauthn4j.server.ServerProperty

/**
 * WebAuthn / passkey relying party. The cryptographic verification of the registration and
 * authentication ceremonies is delegated to webauthn4j (Apache-2.0, FIDO-conformance tested);
 * this class owns the challenge lifecycle, credential persistence and session issuance, and it
 * additionally verifies the origin itself so a passkey response can only ever be accepted for
 * the configured origin.
 *
 * Security invariants:
 *  - Challenges are single-use, short-lived and bound to the RP id + origin.
 *  - A passkey credential is a public-key credential only — it never grants the vault: after a
 *    successful authentication the server issues the same kind of session token as password
 *    login, and the Master Vault Key still requires the recovery key / device key to unlock.
 *  - Only ciphertext and public-key material are persisted; no signing key or vault secret.
 */
class WebAuthnService(
    private val store: Store,
    private val rpId: String,
    private val origin: String,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private val webAuthnManager: WebAuthnManager = WebAuthnManager.createNonStrictWebAuthnManager()
    private val objectConverter = ObjectConverter()

    companion object {
        const val CHALLENGE_TTL_MILLIS: Long = 5 * 60 * 1000L // 5 minutes
    }

    // ------------------------------------------------------------- registration

    /** Starts a passkey registration, returning the `PublicKeyCredentialCreationOptions` JSON. */
    fun startRegistration(userId: String, username: String): String {
        val challenge = Security.randomBytes(32)
        val challengeId = Security.base64Url(challenge)
        store.insertWebAuthnChallenge(
            WebAuthnChallengeRecord(challengeId, userId, challenge, clock() + CHALLENGE_TTL_MILLIS)
        )
        val options = PublicKeyCredentialCreationOptions(
            PublicKeyCredentialRpEntity(rpId, "Signiffia"),
            PublicKeyCredentialUserEntity(userId.toByteArray(), username, username),
            DefaultChallenge(challenge),
            listOf(
                PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.ES256),
                PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.RS256),
            ),
            null, // timeout
            emptyList(), // excludeCredentials
            AuthenticatorSelectionCriteria(
                null,
                ResidentKeyRequirement.REQUIRED,
                UserVerificationRequirement.REQUIRED,
            ),
            AttestationConveyancePreference.NONE,
            null, // extensions
        )
        return writeJson(options)
    }

    /**
     * Completes a passkey registration. Returns the user id on success, or null when the
     * response is invalid, replayed, expired or from a wrong origin.
     */
    fun finishRegistration(responseJson: String): String? {
        return runCatching {
            val registrationData = webAuthnManager.parseRegistrationResponseJSON(responseJson)
            val clientData = registrationData.collectedClientData ?: return null
            checkOrigin(clientData.origin)
            val challengeBytes = clientData.challenge.value
            val challenge = takeChallenge(challengeBytes) ?: return null
            val params = RegistrationParameters(
                ServerProperty(Origin(origin), rpId, DefaultChallenge(challengeBytes)),
                listOf(
                    PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.ES256),
                    PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.RS256),
                ),
                true, // user verification required
                true, // user presence required
            )
            val verified = webAuthnManager.verify(registrationData, params)
            val attestation = verified.attestationObject ?: return null
            val credential = CredentialRecordImpl(
                attestation,
                verified.collectedClientData!!,
                verified.clientExtensions,
                verified.transports,
            )
            val attestedCredentialData = attestation.authenticatorData?.attestedCredentialData ?: return null
            val credentialId = Security.base64Url(attestedCredentialData.credentialId)
            val userId = challenge.userId ?: return null
            store.insertWebAuthnCredential(
                WebAuthnCredentialRecord(
                    credentialId = credentialId,
                    userId = userId,
                    recordJson = writeJson(credential),
                    signCount = attestation.authenticatorData!!.signCount,
                    createdAt = clock(),
                )
            )
            userId
        }.getOrNull()
    }

    // ------------------------------------------------------------- authentication

    /** Starts passkey authentication for a user, returning the request options JSON or null. */
    fun startAuthentication(username: String): String? {
        val user = store.findUserByName(username) ?: return null
        val credentials = store.listWebAuthnCredentials(user.userId)
        if (credentials.isEmpty()) return null
        val challenge = Security.randomBytes(32)
        val challengeId = Security.base64Url(challenge)
        store.insertWebAuthnChallenge(
            WebAuthnChallengeRecord(challengeId, user.userId, challenge, clock() + CHALLENGE_TTL_MILLIS)
        )
        val allowCredentials = credentials.map {
            PublicKeyCredentialDescriptor(
                PublicKeyCredentialType.PUBLIC_KEY,
                Security.decodeBase64Url(it.credentialId),
                setOf<AuthenticatorTransport>(),
            )
        }
        val options = PublicKeyCredentialRequestOptions(
            DefaultChallenge(challenge),
            null, // timeout
            rpId,
            allowCredentials,
            UserVerificationRequirement.REQUIRED,
            null, // extensions
        )
        return writeJson(options)
    }

    /**
     * Completes passkey authentication and issues a session (same kind as password login).
     * Returns null on any failure: invalid signature, replay, expiry, wrong origin or revoked
     * device. A successful passkey login does NOT decrypt the vault — that still requires the
     * recovery key or the device key.
     */
    fun finishAuthentication(responseJson: String): SessionResult? {
        return runCatching {
            val authData = webAuthnManager.parseAuthenticationResponseJSON(responseJson)
            val clientData = authData.collectedClientData ?: return null
            checkOrigin(clientData.origin)
            val challengeBytes = clientData.challenge.value
            val challenge = takeChallenge(challengeBytes) ?: return null
            val credentialId = Security.base64Url(authData.credentialId)
            val stored = store.findWebAuthnCredential(credentialId) ?: return null
            val credential = readCredential(stored.recordJson) ?: return null
            val params = AuthenticationParameters(
                ServerProperty(Origin(origin), rpId, DefaultChallenge(challengeBytes)),
                credential,
                null, // allowCredentials (already selected by the authenticator)
                true, // user verification required
                true, // user presence required
            )
            val verified = webAuthnManager.verify(authData, params)
            store.updateWebAuthnSignCount(credentialId, verified.authenticatorData!!.signCount)
            issueSession(stored.userId)
        }.getOrNull()
    }

    // ------------------------------------------------------------- internals

    private fun issueSession(userId: String): SessionResult {
        val token = Security.generateSessionToken()
        val deviceId = Security.randomId()
        val now = clock()
        store.insertSession(
            Security.tokenHash(token),
            SessionRecord(userId, deviceId, now, now + ServerCore.SESSION_TTL_MILLIS),
        )
        store.insertDevice(
            DeviceRecord(deviceId, userId, "Novi uređaj (passkey)", "android", now, now, revoked = false),
        )
        return SessionResult(userId, token, deviceId)
    }

    /** Loads and consumes a stored challenge; single-use and short-lived. */
    private fun takeChallenge(challengeBytes: ByteArray): WebAuthnChallengeRecord? {
        val challengeId = Security.base64Url(challengeBytes)
        val stored = store.findWebAuthnChallenge(challengeId) ?: return null
        if (stored.expiresAt < clock()) return null
        store.deleteWebAuthnChallenge(challengeId)
        return stored
    }

    private fun checkOrigin(actual: Origin) {
        if (actual.toString() != origin) throw IllegalArgumentException("Neispravan origin.")
    }

    private fun writeJson(value: Any): String =
        objectConverter.jsonMapper.writeValueAsString(value)

    private fun readCredential(json: String): CredentialRecordImpl? = runCatching {
        objectConverter.jsonMapper.readValue(json, CredentialRecordImpl::class.java)
    }.getOrNull()
}
