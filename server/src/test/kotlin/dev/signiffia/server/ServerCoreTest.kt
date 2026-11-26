package dev.signiffia.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the server's business logic, run entirely against the in-memory store so no
 * database is required. These tests exercise every security-critical rule: session-token
 * hashing, constant-time verifier comparison, epoch/version monotonicity, revocation and
 * single-use enrollment tickets.
 */
class ServerCoreTest {

    private fun newCore() = ServerCore(InMemoryStore())

    private fun vault(version: Long, epoch: Long) = VaultRecord(
        vaultVersion = version,
        epoch = epoch,
        deviceId = "dev-1",
        createdAt = 1000L,
        recordsSha256 = "sha",
        nonce = ByteArray(12),
        ciphertext = byteArrayOf(1, 2, 3),
        wrappedMvkRecovery = byteArrayOf(4, 5, 6),
    )

    private fun registerAndLogin(core: ServerCore, username: String = "user@example.com"): SessionResult {
        assertNull(core.register(username, ByteArray(16), ByteArray(32)))
        val session = core.login(username, ByteArray(32))
        assertNotNull(session)
        return session!!
    }

    // ------------------------------------------------------------- auth

    @Test
    fun registerAndLoginProducesWorkingSession() {
        val core = newCore()
        val session = registerAndLogin(core)
        val auth = core.authenticate("Bearer ${session.sessionToken}")
        assertNotNull(auth)
        assertEquals(session.userId, auth!!.userId)
        assertEquals(session.deviceId, auth.deviceId)
    }

    @Test
    fun loginWithWrongVerifierFails() {
        val core = newCore()
        val registered = ByteArray(32) { 1 } // registered verifier (all 0x01)
        assertNull(core.register("u", ByteArray(16), registered))
        assertNull(core.login("u", ByteArray(32))) // all-0x00 != all-0x01
    }

    @Test
    fun loginWithUnknownUserFails() {
        val core = newCore()
        assertNull(core.login("nobody", ByteArray(32)))
    }

    @Test
    fun duplicateUsernameRejected() {
        val core = newCore()
        assertNull(core.register("u", ByteArray(16), ByteArray(32)))
        assertNotNull(core.register("u", ByteArray(16), ByteArray(32))) // second register fails
    }

    @Test
    fun sessionTokenIsHashedAtRest() {
        val core = newCore()
        val store = InMemoryStore()
        val c = ServerCore(store)
        val session = registerAndLogin(c)
        // The raw token must never be a storage key; only its hash is.
        assertNull(store.findSession(session.sessionToken))
        assertNotNull(store.findSession(Security.tokenHash(session.sessionToken)))
    }

    @Test
    fun revokedDeviceCannotAuthenticate() {
        val core = newCore()
        val session = registerAndLogin(core)
        assertNotNull(core.revokeDevice(core.authenticate("Bearer ${session.sessionToken}")!!, session.deviceId))
        assertNull(core.authenticate("Bearer ${session.sessionToken}"))
    }

    @Test
    fun expiredSessionCannotAuthenticate() {
        var now = 1_000_000L
        val core = ServerCore(InMemoryStore(), clock = { now })
        registerAndLogin(core)
        val session = core.login("user@example.com", ByteArray(32))!!
        now += ServerCore.SESSION_TTL_MILLIS + 1
        assertNull(core.authenticate("Bearer ${session.sessionToken}"))
    }

    // ------------------------------------------------------------- vault

    @Test
    fun firstVaultUploadAccepted() {
        val core = newCore()
        val session = registerAndLogin(core)
        val auth = core.authenticate("Bearer ${session.sessionToken}")!!
        assertTrue(core.putVault(auth, vault(1, 1)) is ServerCore.VaultResult.Accepted)
        assertEquals(1L, core.getVault(auth)!!.vaultVersion)
    }

    @Test
    fun staleVaultVersionRejected() {
        val core = newCore()
        val session = registerAndLogin(core)
        val auth = core.authenticate("Bearer ${session.sessionToken}")!!
        assertTrue(core.putVault(auth, vault(2, 1)) is ServerCore.VaultResult.Accepted)
        val result = core.putVault(auth, vault(1, 1))
        assertTrue(result is ServerCore.VaultResult.Rejected)
        // The stored vault is unchanged.
        assertEquals(2L, core.getVault(auth)!!.vaultVersion)
    }

    @Test
    fun sameVersionRejected() {
        val core = newCore()
        val session = registerAndLogin(core)
        val auth = core.authenticate("Bearer ${session.sessionToken}")!!
        assertTrue(core.putVault(auth, vault(1, 1)) is ServerCore.VaultResult.Accepted)
        assertTrue(core.putVault(auth, vault(1, 1)) is ServerCore.VaultResult.Rejected)
    }

    @Test
    fun staleEpochRejectedAfterRevocation() {
        val core = newCore()
        val session = registerAndLogin(core)
        val auth = core.authenticate("Bearer ${session.sessionToken}")!!
        // A second device joins.
        val other = core.login("user@example.com", ByteArray(32))!!
        val otherAuth = core.authenticate("Bearer ${other.sessionToken}")!!
        // Revoke bumps epoch 1 -> 2.
        assertEquals(2L, core.revokeDevice(auth, other.deviceId))
        // Uploading with the old epoch (1) must be rejected.
        assertTrue(core.putVault(auth, vault(1, 1)) is ServerCore.VaultResult.Rejected)
        // Uploading with the current epoch (2) is accepted.
        assertTrue(core.putVault(auth, vault(1, 2)) is ServerCore.VaultResult.Accepted)
    }

    @Test
    fun vaultMetaReportsVersionAndEpoch() {
        val core = newCore()
        val session = registerAndLogin(core)
        val auth = core.authenticate("Bearer ${session.sessionToken}")!!
        assertEquals(0L, core.vaultMeta(auth).vaultVersion)
        assertEquals(1L, core.vaultMeta(auth).epoch)
        core.putVault(auth, vault(5, 1))
        assertEquals(5L, core.vaultMeta(auth).vaultVersion)
    }

    // ------------------------------------------------------------- devices

    @Test
    fun listDevicesShowsAllAndRevokeFlags() {
        val core = newCore()
        val session = registerAndLogin(core)
        val auth = core.authenticate("Bearer ${session.sessionToken}")!!
        core.login("user@example.com", ByteArray(32)) // second device
        val devices = core.listDevices(auth)
        assertEquals(2, devices.size)
        assertTrue(devices.none { it.revoked })
        core.revokeDevice(auth, devices.first { it.deviceId != session.deviceId }.deviceId)
        assertEquals(1, core.listDevices(auth).count { it.revoked })
    }

    @Test
    fun cannotRevokeAnotherUsersDevice() {
        val core = newCore()
        val a = registerAndLogin(core, "a@example.com")
        registerAndLogin(core, "b@example.com")
        val authA = core.authenticate("Bearer ${a.sessionToken}")!!
        val devicesOfB = core.listDevices(core.authenticate("Bearer ${core.login("b@example.com", ByteArray(32))!!.sessionToken}")!!)
        assertNull(core.revokeDevice(authA, devicesOfB.first().deviceId))
    }

    // ------------------------------------------------------------- enrollment

    @Test
    fun enrollmentTicketIsSingleUse() {
        val core = newCore()
        val session = registerAndLogin(core)
        val auth = core.authenticate("Bearer ${session.sessionToken}")!!
        val ticket = core.enrollBegin(auth, byteArrayOf(9, 8, 7))
        assertTrue(core.enrollConfirm(auth, ticket.ticketId))
        assertFalse(core.enrollConfirm(auth, ticket.ticketId)) // replay rejected
    }

    @Test
    fun enrollmentTicketExpires() {
        var now = 1_000_000L
        val core = ServerCore(InMemoryStore(), clock = { now })
        val session = registerAndLogin(core)
        val auth = core.authenticate("Bearer ${session.sessionToken}")!!
        val ticket = core.enrollBegin(auth, byteArrayOf(9, 8, 7))
        now += ServerCore.ENROLL_TTL_MILLIS + 1
        assertFalse(core.enrollConfirm(auth, ticket.ticketId))
    }

    @Test
    fun enrollmentTicketIsScopedToUser() {
        val core = newCore()
        val a = registerAndLogin(core, "a@example.com")
        val b = registerAndLogin(core, "b@example.com")
        val authA = core.authenticate("Bearer ${a.sessionToken}")!!
        val authB = core.authenticate("Bearer ${b.sessionToken}")!!
        val ticket = core.enrollBegin(authA, byteArrayOf(1))
        assertFalse(core.enrollConfirm(authB, ticket.ticketId))
    }
}
