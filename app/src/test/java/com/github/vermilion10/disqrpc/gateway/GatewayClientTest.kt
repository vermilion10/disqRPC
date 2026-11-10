package com.github.vermilion10.disqrpc.gateway

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayClientTest {

    private class FakeConnection(
        val url: String,
        val listener: GatewayTransport.Listener
    ) : GatewayTransport.Connection {
        val sent = mutableListOf<JSONObject>()
        var closedWith: Int? = null

        override fun send(text: String): Boolean {
            if (closedWith != null) return false
            sent += JSONObject(text)
            return true
        }

        override fun close(code: Int, reason: String) {
            closedWith = code
        }

        fun ops() = sent.map { it.getInt("op") }
        fun last(op: Int) = sent.last { it.getInt("op") == op }

        fun receive(json: String) = listener.onMessage(json)
        fun hello(interval: Long = HEARTBEAT) = receive("""{"op":10,"d":{"heartbeat_interval":$interval}}""")
        fun ready(seq: Int = 1) = receive(
            """{"op":0,"s":$seq,"t":"READY","d":{"session_id":"sess","resume_gateway_url":"wss://resume.test/",""" +
                """"user":{"username":"name","global_name":null}}}"""
        )
        fun dispatch(seq: Int, type: String) = receive("""{"op":0,"s":$seq,"t":"$type","d":{}}""")
    }

    private class FakeTransport : GatewayTransport {
        val connections = mutableListOf<FakeConnection>()
        val current get() = connections.last()

        override fun open(
            url: String,
            headers: Map<String, String>,
            listener: GatewayTransport.Listener
        ): GatewayTransport.Connection = FakeConnection(url, listener).also { connections += it }
    }

    private val transport = FakeTransport()

    private fun TestScope.client(token: String? = "user-token", maxAttempts: Int = 10) = GatewayClient(
        transport = transport,
        identityProvider = { GatewayIdentity.fromToken(token) },
        scope = backgroundScope,
        random = Random(42),
        config = GatewayClient.Config(maxReconnectAttempts = maxAttempts)
    )

    private fun presence(name: String) = JSONObject()
        .put("status", "online")
        .put("activities", org.json.JSONArray().put(JSONObject().put("name", name).put("type", 0)))

    private fun TestScope.connectReady(client: GatewayClient): FakeConnection {
        client.connect()
        runCurrent()
        val conn = transport.current
        conn.hello()
        conn.ready()
        runCurrent()
        return conn
    }

    @Test
    fun identifiesAfterHelloAndCarriesQueuedPresence() = runTest {
        val client = client()
        client.updatePresence(presence("Game A"))
        client.connect()
        runCurrent()

        val conn = transport.current
        assertTrue(conn.url.startsWith("wss://gateway.discord.gg/?v=10"))
        assertTrue(client.state.value is GatewayState.Connecting)

        conn.hello()
        runCurrent()
        val identify = conn.last(GatewayClient.OP_IDENTIFY).getJSONObject("d")
        assertEquals("user-token", identify.getString("token"))
        assertEquals("Game A", identify.getJSONObject("presence").getJSONArray("activities").getJSONObject(0).getString("name"))

        conn.ready()
        runCurrent()
        assertEquals(GatewayState.Ready("name"), client.state.value)
        // Presence went out with IDENTIFY, so no separate op 3 is needed.
        assertFalse(conn.ops().contains(GatewayClient.OP_PRESENCE_UPDATE))
    }

    @Test
    fun sendsPresenceOnlyWhenItChanges() = runTest {
        val client = client()
        val conn = connectReady(client)

        client.updatePresence(presence("Game A"))
        client.updatePresence(presence("Game A"))
        client.updatePresence(presence("Game B"))
        runCurrent()

        val updates = conn.sent.filter { it.getInt("op") == GatewayClient.OP_PRESENCE_UPDATE }
        assertEquals(2, updates.size)
    }

    @Test
    fun missedHeartbeatAckResumesOnResumeUrl() = runTest {
        val client = client()
        val conn = connectReady(client)
        conn.dispatch(seq = 7, type = "MESSAGE_CREATE")

        advanceTimeBy(HEARTBEAT + 1)
        runCurrent()
        val beat = conn.last(GatewayClient.OP_HEARTBEAT)
        assertEquals(7, beat.getInt("d"))

        // No ACK before the next beat: the connection is treated as dead.
        advanceTimeBy(HEARTBEAT)
        runCurrent()
        assertEquals(GatewayClient.CLOSE_RESUMABLE, conn.closedWith)

        advanceTimeBy(60_000)
        runCurrent()
        val second = transport.current
        assertTrue(second !== conn)
        assertTrue(second.url.startsWith("wss://resume.test/?v=10"))

        second.hello()
        runCurrent()
        val resume = second.last(GatewayClient.OP_RESUME).getJSONObject("d")
        assertEquals("sess", resume.getString("session_id"))
        assertEquals(7, resume.getInt("seq"))
        assertFalse(second.ops().contains(GatewayClient.OP_IDENTIFY))
    }

    @Test
    fun ackedHeartbeatsKeepConnectionAlive() = runTest {
        val client = client()
        val conn = connectReady(client)
        repeat(5) {
            advanceTimeBy(HEARTBEAT)
            runCurrent()
            conn.receive("""{"op":11}""")
            runCurrent()
        }
        assertNull(conn.closedWith)
        assertEquals(1, transport.connections.size)
    }

    @Test
    fun presenceChangedWhileDisconnectedIsSentAfterResume() = runTest {
        val client = client()
        val conn = connectReady(client)

        conn.listener.onFailure(RuntimeException("network down"))
        runCurrent()
        client.updatePresence(presence("Game C"))
        advanceTimeBy(60_000)
        runCurrent()

        val second = transport.current
        second.hello()
        second.dispatch(seq = 2, type = "RESUMED")
        runCurrent()

        assertTrue(client.state.value is GatewayState.Ready)
        val update = second.last(GatewayClient.OP_PRESENCE_UPDATE).getJSONObject("d")
        assertEquals("Game C", update.getJSONArray("activities").getJSONObject(0).getString("name"))
    }

    @Test
    fun authFailureIsFatal() = runTest {
        val client = client()
        client.connect()
        runCurrent()
        transport.current.listener.onClosed(4004, "Authentication failed")
        runCurrent()
        advanceTimeBy(120_000)
        runCurrent()

        assertTrue(client.state.value is GatewayState.Failed)
        assertEquals(1, transport.connections.size)
    }

    @Test
    fun nonResumableInvalidSessionIdentifiesFresh() = runTest {
        val client = client()
        val conn = connectReady(client)

        conn.receive("""{"op":9,"d":false}""")
        runCurrent()
        advanceTimeBy(5_001)
        runCurrent()

        val second = transport.current
        assertTrue(second.url.startsWith("wss://gateway.discord.gg/"))
        second.hello()
        runCurrent()
        assertTrue(second.ops().contains(GatewayClient.OP_IDENTIFY))
        assertFalse(second.ops().contains(GatewayClient.OP_RESUME))
    }

    @Test
    fun callbacksFromReplacedSocketAreIgnored() = runTest {
        val client = client()
        val old = connectReady(client)
        old.receive("""{"op":7,"d":null}""")
        runCurrent()
        val fresh = transport.current
        assertTrue(fresh !== old)

        old.listener.onClosed(4004, "late close from old socket")
        runCurrent()
        assertFalse(client.state.value is GatewayState.Failed)
    }

    @Test
    fun disconnectClosesNormallyAndStopsReconnecting() = runTest {
        val client = client()
        val conn = connectReady(client)

        client.disconnect()
        runCurrent()
        advanceTimeBy(120_000)
        runCurrent()

        assertEquals(GatewayClient.CLOSE_NORMAL, conn.closedWith)
        assertEquals(GatewayState.Disconnected, client.state.value)
        assertEquals(1, transport.connections.size)
    }

    @Test
    fun givesUpAfterMaxAttempts() = runTest {
        val client = client(maxAttempts = 3)
        client.connect()
        runCurrent()
        repeat(4) {
            transport.current.listener.onFailure(RuntimeException("offline"))
            runCurrent()
            advanceTimeBy(120_000)
            runCurrent()
        }
        assertTrue(client.state.value is GatewayState.Failed)
        assertEquals(4, transport.connections.size)
    }

    @Test
    fun offlineHoldsReconnectsWithoutSpendingAttempts() = runTest {
        val client = client(maxAttempts = 3)
        val conn = connectReady(client)

        client.setNetworkAvailable(false)
        conn.listener.onFailure(RuntimeException("connection abort"))
        runCurrent()
        // A long outage must not exhaust the retry budget.
        advanceTimeBy(30 * 60_000L)
        runCurrent()
        assertEquals(1, transport.connections.size)
        assertTrue(client.state.value is GatewayState.Connecting)

        client.setNetworkAvailable(true)
        runCurrent()
        // Reconnects immediately, no backoff wait.
        assertEquals(2, transport.connections.size)
        assertTrue(transport.current.url.startsWith("wss://resume.test/"))
    }

    @Test
    fun networkReturningSkipsPendingBackoff() = runTest {
        val client = client()
        client.connect()
        runCurrent()
        repeat(5) {
            transport.current.listener.onFailure(RuntimeException("dns"))
            runCurrent()
            advanceTimeBy(60_000)
            runCurrent()
        }
        transport.current.listener.onFailure(RuntimeException("dns"))
        runCurrent()
        val before = transport.connections.size

        client.setNetworkAvailable(false)
        client.setNetworkAvailable(true)
        runCurrent()
        assertEquals(before + 1, transport.connections.size)
    }

    @Test
    fun missingTokenFailsWithoutOpeningSocket() = runTest {
        val client = client(token = null)
        client.connect()
        runCurrent()
        assertTrue(client.state.value is GatewayState.Failed)
        assertTrue(transport.connections.isEmpty())
    }

    @Test
    fun botTokenPrefixIsStrippedForIdentify() {
        val id = GatewayIdentity.fromToken("Bot abc.def")!!
        assertTrue(id.isBot)
        assertEquals("abc.def", id.identifyPayload(null).getString("token"))
        assertEquals(0, id.identifyPayload(null).getInt("intents"))
    }

    private companion object {
        const val HEARTBEAT = 41_250L
    }
}
