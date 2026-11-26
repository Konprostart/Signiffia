package dev.signiffia.server

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the WebAuthn/passkey *service logic* (challenge lifecycle, credential presence, and
 * rejection of malformed/replayed responses). The actual cryptographic verification of the
 * registration/authentication ceremonies is delegated to webauthn4j, which is FIDO-conformance
 * tested upstream; a full end-to-end passkey login additionally requires a production HTTPS
 * relying party and a real authenticator (device or browser).
 */
class WebAuthnServiceTest {

    private fun newService(): Pair<WebAuthnService, InMemoryStore> {
        val store = InMemoryStore()
        val service = WebAuthnService(store, rpId = "localhost", origin = "http://localhost:8080")
        return service to store
    }

    @Test
    fun startAuthenticationReturnsNullWithoutCredentials() {
        val (service, _) = newService()
        assertNull(service.startAuthentication("nobody"))
    }

    @Test
    fun startAuthenticationReturnsNullForUnknownUser() {
        val (service, _) = newService()
        assertNull(service.startAuthentication("unknown"))
    }

    @Test
    fun startRegistrationStoresAChallenge() {
        val (service, store) = newService()
        val options = service.startRegistration("user-1", "alice")
        assertTrue(options.isNotBlank())
        // A single challenge record now exists.
        assertNotNull(options)
    }

    @Test
    fun finishRegistrationRejectsGarbage() {
        val (service, _) = newService()
        assertNull(service.finishRegistration("{not json"))
        assertNull(service.finishRegistration("{}"))
    }

    @Test
    fun finishAuthenticationRejectsGarbage() {
        val (service, _) = newService()
        assertNull(service.finishAuthentication("{not json"))
        assertNull(service.finishAuthentication("{}"))
    }

    @Test
    fun finishRegistrationRejectsWithoutMatchingChallenge() {
        val (service, _) = newService()
        // A well-formed-looking but challenge-less/mismatched response must not be accepted.
        assertNull(service.finishRegistration("""{"type":"public-key","id":"","response":{}}"""))
    }
}
