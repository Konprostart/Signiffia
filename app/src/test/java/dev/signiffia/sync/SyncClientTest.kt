package dev.signiffia.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SyncClientTest {

    private class FakeTransport : SyncTransport {
        var lastMethod: String = ""
        var lastPath: String = ""
        var lastBody: ByteArray = ByteArray(0)
        var lastAuth: String? = null
        var handler: ((String, String, ByteArray, String?) -> HttpResponse)? = null

        override fun request(method: String, path: String, body: ByteArray, authToken: String?): HttpResponse {
            lastMethod = method
            lastPath = path
            lastBody = body
            lastAuth = authToken
            return handler?.invoke(method, path, body, authToken) ?: HttpResponse(500, "no handler".toByteArray())
        }
    }

    private val session = SessionInfo(userId = "u1", sessionToken = "tok-1", deviceId = "dev-1")

    @Test
    fun `fetchSalt sends username and parses salt`() {
        val t = FakeTransport()
        t.handler = { _, path, _, _ ->
            assertEquals("/auth/salt", path)
            HttpResponse(200, WireCodec.Writer().bytes(ByteArray(16) { 3 }).toBytes())
        }
        val salt = SyncClient(t).fetchSalt("alice")
        assertArrayEquals(ByteArray(16) { 3 }, salt)
        assertEquals("GET", t.lastMethod)
    }

    @Test
    fun `register sends username salt and verifier`() {
        val t = FakeTransport()
        t.handler = { _, path, _, _ ->
            assertEquals("/auth/register", path)
            HttpResponse(200, ByteArray(0))
        }
        val salt = ByteArray(16) { 1 }
        val verifier = ByteArray(32) { 2 }
        SyncClient(t).register("alice", salt, verifier)
        val reader = WireCodec.Reader(t.lastBody)
        assertEquals("alice", reader.string())
        assertArrayEquals(salt, reader.bytes())
        assertArrayEquals(verifier, reader.bytes())
    }

    @Test
    fun `login parses session`() {
        val t = FakeTransport()
        t.handler = { _, _, _, _ ->
            HttpResponse(
                200,
                WireCodec.Writer().string("u1").string("tok-1").string("dev-1").toBytes(),
            )
        }
        val result = SyncClient(t).login("alice", ByteArray(32) { 1 })
        assertEquals("u1", result.userId)
        assertEquals("tok-1", result.sessionToken)
        assertEquals("dev-1", result.deviceId)
    }

    @Test
    fun `login failure surfaces error message`() {
        val t = FakeTransport()
        t.handler = { _, _, _, _ -> HttpResponse(401, "wrong credentials".toByteArray()) }
        try {
            SyncClient(t).login("alice", ByteArray(32))
            fail("Expected SyncException")
        } catch (e: SyncException) {
            assertTrue(e.message!!.contains("wrong credentials"))
        }
    }

    @Test
    fun `uploadVault encodes snapshot and includes auth header`() {
        val t = FakeTransport()
        t.handler = { method, path, _, auth ->
            assertEquals("PUT", method)
            assertEquals("/vault/object", path)
            assertEquals("tok-1", auth)
            HttpResponse(200, ByteArray(0))
        }
        val snapshot = VaultSnapshot(
            vaultVersion = 3,
            epoch = 2,
            deviceId = "dev-1",
            createdAt = 123456L,
            recordsSha256 = "abc",
            nonce = ByteArray(12) { 4 },
            ciphertext = ByteArray(64) { 5 },
            wrappedMvkRecovery = ByteArray(48) { 6 },
        )
        SyncClient(t).uploadVault(session, snapshot)

        val reader = WireCodec.Reader(t.lastBody)
        assertEquals(3L, reader.long())
        assertEquals(2L, reader.long())
        assertEquals("dev-1", reader.string())
        assertEquals(123456L, reader.long())
        assertEquals("abc", reader.string())
        assertArrayEquals(snapshot.nonce, reader.bytes())
        assertArrayEquals(snapshot.ciphertext, reader.bytes())
        assertArrayEquals(snapshot.wrappedMvkRecovery, reader.bytes())
    }

    @Test
    fun `downloadVault parses snapshot`() {
        val t = FakeTransport()
        t.handler = { _, _, _, _ ->
            HttpResponse(
                200,
                WireCodec.Writer()
                    .long(3).long(2).string("dev-1").long(123456L).string("abc")
                    .bytes(ByteArray(12) { 4 }).bytes(ByteArray(64) { 5 }).bytes(ByteArray(48) { 6 })
                    .toBytes(),
            )
        }
        val snapshot = SyncClient(t).downloadVault(session)
        assertEquals(3L, snapshot.vaultVersion)
        assertEquals(2L, snapshot.epoch)
        assertArrayEquals(ByteArray(12) { 4 }, snapshot.nonce)
        assertArrayEquals(ByteArray(64) { 5 }, snapshot.ciphertext)
        assertArrayEquals(ByteArray(48) { 6 }, snapshot.wrappedMvkRecovery)
    }

    @Test
    fun `downloadVault 404 becomes not found`() {
        val t = FakeTransport()
        t.handler = { _, _, _, _ -> HttpResponse(404, "missing".toByteArray()) }
        try {
            SyncClient(t).downloadVault(session)
            fail("Expected SyncException")
        } catch (e: SyncException) {
            assertEquals(404, e.code)
        }
    }

    @Test
    fun `listDevices parses device list`() {
        val t = FakeTransport()
        t.handler = { _, _, _, _ ->
            HttpResponse(
                200,
                WireCodec.Writer()
                    .int(2)
                    .string("d1").string("Phone").string("android").long(1).long(2).int(0)
                    .string("d2").string("Tablet").string("android").long(3).long(4).int(1)
                    .toBytes(),
            )
        }
        val devices = SyncClient(t).listDevices(session)
        assertEquals(2, devices.size)
        assertEquals("Phone", devices[0].name)
        assertFalse(devices[0].revoked)
        assertEquals("Tablet", devices[1].name)
        assertTrue(devices[1].revoked)
    }

    @Test
    fun `revokeDevice returns new epoch`() {
        val t = FakeTransport()
        t.handler = { _, path, _, _ ->
            assertEquals("/devices/revoke", path)
            HttpResponse(200, WireCodec.Writer().long(7).toBytes())
        }
        assertEquals(7L, SyncClient(t).revokeDevice(session, "d1"))
        assertEquals("d1", WireCodec.Reader(t.lastBody).string())
    }

    @Test
    fun `enrollBegin parses ticket`() {
        val t = FakeTransport()
        t.handler = { _, _, _, _ ->
            HttpResponse(
                200,
                WireCodec.Writer().string("tid").long(999L).bytes(ByteArray(16) { 2 }).toBytes(),
            )
        }
        val ticket = SyncClient(t).enrollBegin(session, ByteArray(65) { 0 })
        assertEquals("tid", ticket.ticketId)
        assertEquals(999L, ticket.expiresAtMillis)
        assertArrayEquals(ByteArray(16) { 2 }, ticket.nonce)
    }

    @Test
    fun `fetchVaultMeta parses version and epoch`() {
        val t = FakeTransport()
        t.handler = { _, path, _, auth ->
            assertEquals("/vault/meta", path)
            assertEquals("tok-1", auth)
            HttpResponse(200, WireCodec.Writer().long(4).long(3).toBytes())
        }
        val meta = SyncClient(t).fetchVaultMeta(session)
        assertEquals(4L, meta.vaultVersion)
        assertEquals(3L, meta.epoch)
    }

    @Test
    fun `enrollConfirm sends ticket and wrapped mvk`() {
        val t = FakeTransport()
        t.handler = { _, path, _, auth ->
            assertEquals("/enroll/confirm", path)
            assertEquals("tok-1", auth)
            HttpResponse(200, ByteArray(0))
        }
        val wrapped = ByteArray(48) { 9 }
        SyncClient(t).enrollConfirm(session, "tid", wrapped)
        val reader = WireCodec.Reader(t.lastBody)
        assertEquals("tid", reader.string())
        assertArrayEquals(wrapped, reader.bytes())
    }
}
