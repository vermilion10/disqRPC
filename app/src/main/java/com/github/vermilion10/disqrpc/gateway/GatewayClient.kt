package com.github.vermilion10.disqrpc.gateway

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.min
import kotlin.random.Random

sealed interface GatewayState {
    data object Disconnected : GatewayState
    /** Opening a socket or waiting to retry. [attempt] is 0 for the first connect. */
    data class Connecting(val attempt: Int) : GatewayState
    data class Ready(val username: String) : GatewayState
    data class Failed(val reason: String) : GatewayState
}

fun interface GatewayLog {
    enum class Level { DEBUG, INFO, WARN, ERROR }

    fun log(level: Level, message: String)

    companion object {
        val None = GatewayLog { _, _ -> }
    }
}

/**
 * Discord gateway session: handshake, heartbeats with a missed-ACK watchdog, session
 * resume, and reconnect with backoff.
 *
 * Every piece of mutable state is owned by one event-loop coroutine. Socket callbacks and
 * timers only post events, and each socket carries a generation number so callbacks from
 * a socket that has already been replaced are dropped.
 */
class GatewayClient(
    private val transport: GatewayTransport,
    private val identityProvider: () -> GatewayIdentity?,
    private val scope: CoroutineScope,
    private val log: GatewayLog = GatewayLog.None,
    private val random: Random = Random.Default,
    private val config: Config = Config()
) {

    data class Config(
        val gatewayUrl: String = "wss://gateway.discord.gg",
        val handshakeTimeoutMs: Long = 30_000,
        val maxReconnectAttempts: Int = 10,
        val baseBackoffMs: Long = 1_000,
        val maxBackoffMs: Long = 60_000
    )

    private val _state = MutableStateFlow<GatewayState>(GatewayState.Disconnected)
    val state: StateFlow<GatewayState> = _state.asStateFlow()

    private sealed interface Event {
        data object Connect : Event
        data object Disconnect : Event
        data class SetPresence(val presence: JSONObject) : Event
        data class Opened(val gen: Int) : Event
        data class Message(val gen: Int, val text: String) : Event
        data class Closed(val gen: Int, val code: Int, val reason: String) : Event
        data class Failure(val gen: Int, val error: Throwable) : Event
        data class HeartbeatDue(val gen: Int) : Event
        data class HandshakeTimeout(val gen: Int) : Event
        data object ReconnectDue : Event
        data class NetworkChanged(val available: Boolean) : Event
    }

    private val events = Channel<Event>(Channel.UNLIMITED)

    // ---- Event-loop state. Only touched from handle(). ----
    private var wanted = false
    private var identity: GatewayIdentity? = null
    private var connection: GatewayTransport.Connection? = null
    private var generation = 0
    private var ready = false
    private var username: String? = null

    private var sessionId: String? = null
    private var resumeUrl: String? = null
    private var sequence: Int? = null

    private var awaitingAck = false
    private var heartbeatJob: Job? = null
    private var handshakeJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    private var networkAvailable = true

    private var presence: JSONObject? = null
    private var sentPresence: String? = null

    init {
        scope.launch { for (event in events) handle(event) }
    }

    fun connect() { events.trySend(Event.Connect) }

    fun disconnect() { events.trySend(Event.Disconnect) }

    /**
     * Sets the presence for this session. Sent now when the session is ready, otherwise
     * applied by the next IDENTIFY or after the next RESUME.
     */
    fun updatePresence(presence: JSONObject) { events.trySend(Event.SetPresence(presence)) }

    /**
     * Feed from the platform's connectivity callback. While offline, reconnects are held
     * and do not count against the attempt limit; when the network returns the client
     * reconnects right away instead of waiting out the backoff.
     */
    fun setNetworkAvailable(available: Boolean) { events.trySend(Event.NetworkChanged(available)) }

    private fun handle(event: Event) {
        when (event) {
            Event.Connect -> onConnectRequested()
            Event.Disconnect -> onDisconnectRequested()
            is Event.SetPresence -> onSetPresence(event.presence)
            is Event.Opened -> if (event.gen == generation) log(GatewayLog.Level.DEBUG, "Socket open, waiting for Hello")
            is Event.Message -> if (event.gen == generation) onMessage(event.text)
            is Event.Closed -> if (event.gen == generation) onSocketClosed(event.code, event.reason)
            is Event.Failure -> if (event.gen == generation) onSocketFailure(event.error)
            is Event.HeartbeatDue -> if (event.gen == generation) onHeartbeatDue()
            is Event.HandshakeTimeout -> if (event.gen == generation && !ready) {
                log(GatewayLog.Level.WARN, "Handshake timed out")
                dropConnection(CLOSE_RESUMABLE, "Handshake timeout")
                scheduleReconnect()
            }
            Event.ReconnectDue -> {
                reconnectJob = null
                if (wanted && connection == null && networkAvailable) openSocket()
            }
            is Event.NetworkChanged -> onNetworkChanged(event.available)
        }
    }

    // ---- Commands ----

    private fun onConnectRequested() {
        if (wanted) {
            log(GatewayLog.Level.DEBUG, "Connect ignored: already connected or connecting")
            return
        }
        val id = identityProvider()
        if (id == null) {
            _state.value = GatewayState.Failed("No token saved")
            return
        }
        identity = id
        wanted = true
        reconnectAttempts = 0
        clearSession()
        openSocket()
    }

    private fun onDisconnectRequested() {
        if (wanted || connection != null) log(GatewayLog.Level.INFO, "Disconnecting (close 1000, session ended)")
        wanted = false
        reconnectJob?.cancel()
        reconnectJob = null
        // 1000 tells Discord the session is over, so the presence disappears right away.
        dropConnection(CLOSE_NORMAL, "User requested")
        clearSession()
        presence = null
        _state.value = GatewayState.Disconnected
    }

    private fun onSetPresence(newPresence: JSONObject) {
        presence = newPresence
        if (ready) {
            sendPresenceIfChanged()
        } else {
            log(GatewayLog.Level.DEBUG, "Presence queued until the session is ready")
        }
    }

    private fun onNetworkChanged(available: Boolean) {
        if (available == networkAvailable) return
        networkAvailable = available
        if (!available) {
            log(GatewayLog.Level.INFO, "Network lost")
            reconnectJob?.cancel()
            reconnectJob = null
            return
        }
        log(GatewayLog.Level.INFO, "Network available")
        if (wanted && connection == null) {
            reconnectJob?.cancel()
            reconnectJob = null
            reconnectAttempts = 0
            openSocket()
        }
    }

    // ---- Socket lifecycle ----

    private fun openSocket() {
        val id = identity ?: return
        val gen = ++generation
        val base = if (canResume()) resumeUrl!! else config.gatewayUrl
        val url = "$base/?v=$API_VERSION&encoding=json"
        ready = false
        awaitingAck = false
        _state.value = GatewayState.Connecting(reconnectAttempts)
        log(GatewayLog.Level.INFO, if (canResume()) "Reconnecting to resume session" else "Connecting to gateway")

        connection = transport.open(url, id.headers, object : GatewayTransport.Listener {
            override fun onOpen() { events.trySend(Event.Opened(gen)) }
            override fun onMessage(text: String) { events.trySend(Event.Message(gen, text)) }
            override fun onClosed(code: Int, reason: String) { events.trySend(Event.Closed(gen, code, reason)) }
            override fun onFailure(error: Throwable) { events.trySend(Event.Failure(gen, error)) }
        })
        handshakeJob = scope.launch {
            delay(config.handshakeTimeoutMs)
            events.send(Event.HandshakeTimeout(gen))
        }
    }

    /** Stops timers and closes the current socket. Later callbacks from it are ignored. */
    private fun dropConnection(code: Int, reason: String) {
        connection?.close(code, reason)
        forgetConnection()
    }

    private fun forgetConnection() {
        stopTimers()
        connection = null
        generation++
        ready = false
    }

    private fun stopTimers() {
        heartbeatJob?.cancel(); heartbeatJob = null
        handshakeJob?.cancel(); handshakeJob = null
    }

    private fun onSocketClosed(code: Int, reason: String) {
        log(GatewayLog.Level.WARN, "Gateway closed: $code ${reason.ifBlank { "" }}".trim())
        forgetConnection()
        when (code) {
            in FATAL_CLOSE_CODES -> fail(closeCodeMessage(code))
            CLOSE_INVALID_SEQ, CLOSE_SESSION_TIMED_OUT -> {
                clearSession()
                scheduleReconnect()
            }
            else -> scheduleReconnect()
        }
    }

    private fun onSocketFailure(error: Throwable) {
        log(GatewayLog.Level.ERROR, "Connection failed: ${error.message ?: error.javaClass.simpleName}")
        forgetConnection()
        scheduleReconnect()
    }

    private fun fail(reason: String) {
        log(GatewayLog.Level.ERROR, reason)
        wanted = false
        reconnectJob?.cancel()
        reconnectJob = null
        clearSession()
        _state.value = GatewayState.Failed(reason)
    }

    private fun scheduleReconnect(delayMs: Long? = null) {
        if (!wanted) return
        if (!networkAvailable) {
            log(GatewayLog.Level.INFO, "Offline, waiting for the network to come back")
            reconnectJob?.cancel()
            reconnectJob = null
            _state.value = GatewayState.Connecting(reconnectAttempts)
            return
        }
        reconnectAttempts++
        if (reconnectAttempts > config.maxReconnectAttempts) {
            fail("Gave up after ${config.maxReconnectAttempts} reconnect attempts")
            return
        }
        val wait = delayMs ?: backoffFor(reconnectAttempts)
        log(GatewayLog.Level.INFO, "Reconnecting in ${wait / 1000.0}s (attempt $reconnectAttempts)")
        _state.value = GatewayState.Connecting(reconnectAttempts)
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(wait)
            events.send(Event.ReconnectDue)
        }
    }

    private fun backoffFor(attempt: Int): Long {
        val exp = config.baseBackoffMs shl min(attempt - 1, 16)
        val capped = min(exp, config.maxBackoffMs)
        return capped / 2 + (random.nextDouble() * capped / 2).toLong()
    }

    // ---- Protocol ----

    private fun onMessage(text: String) {
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            log(GatewayLog.Level.WARN, "Ignoring malformed gateway frame")
            return
        }
        when (val op = json.optInt("op", -1)) {
            OP_DISPATCH -> {
                if (!json.isNull("s")) sequence = json.getInt("s")
                onDispatch(json.optString("t"), json.optJSONObject("d"))
            }
            OP_HEARTBEAT -> sendHeartbeat()
            OP_RECONNECT -> {
                log(GatewayLog.Level.INFO, "Discord asked us to reconnect")
                dropConnection(CLOSE_RESUMABLE, "Reconnect requested")
                scheduleReconnect(delayMs = 0)
            }
            OP_INVALID_SESSION -> {
                val resumable = json.optBoolean("d", false)
                log(GatewayLog.Level.WARN, "Invalid session (resumable=$resumable)")
                if (!resumable) clearSession()
                dropConnection(CLOSE_RESUMABLE, "Invalid session")
                // Discord asks clients to wait 1-5 s before identifying again.
                scheduleReconnect(delayMs = 1_000 + random.nextLong(4_000))
            }
            OP_HELLO -> {
                val interval = json.optJSONObject("d")?.optLong("heartbeat_interval", 0L) ?: 0L
                if (interval <= 0L) {
                    log(GatewayLog.Level.ERROR, "Hello without a heartbeat interval")
                    dropConnection(CLOSE_RESUMABLE, "Bad hello")
                    scheduleReconnect()
                    return
                }
                startHeartbeat(interval)
                if (canResume()) sendResume() else sendIdentify()
            }
            OP_HEARTBEAT_ACK -> awaitingAck = false
            else -> log(GatewayLog.Level.DEBUG, "Unhandled op $op")
        }
    }

    private fun onDispatch(type: String, data: JSONObject?) {
        when (type) {
            "READY" -> {
                sessionId = data?.optString("session_id")?.takeIf { it.isNotBlank() }
                resumeUrl = data?.optString("resume_gateway_url")?.trimEnd('/')?.takeIf { it.isNotBlank() }
                username = data?.optJSONObject("user")?.let(::displayName)
                // IDENTIFY carried the current presence, so nothing is pending.
                sentPresence = presence?.toString()
                onSessionReady("Connected as ${username ?: "unknown user"}")
            }
            "RESUMED" -> {
                onSessionReady("Session resumed")
                sendPresenceIfChanged()
            }
        }
    }

    private fun onSessionReady(message: String) {
        handshakeJob?.cancel()
        handshakeJob = null
        ready = true
        reconnectAttempts = 0
        log(GatewayLog.Level.INFO, message)
        _state.value = GatewayState.Ready(username ?: "unknown user")
    }

    private fun startHeartbeat(intervalMs: Long) {
        heartbeatJob?.cancel()
        val gen = generation
        heartbeatJob = scope.launch {
            // First beat at a random point in the interval, as the gateway docs require.
            delay((intervalMs * random.nextDouble()).toLong())
            while (true) {
                events.send(Event.HeartbeatDue(gen))
                delay(intervalMs)
            }
        }
    }

    private fun onHeartbeatDue() {
        if (awaitingAck) {
            log(GatewayLog.Level.WARN, "No heartbeat ACK, connection is dead. Reconnecting.")
            dropConnection(CLOSE_RESUMABLE, "Heartbeat ACK missed")
            scheduleReconnect()
            return
        }
        sendHeartbeat()
        awaitingAck = true
    }

    private fun sendHeartbeat() {
        send(JSONObject().put("op", OP_HEARTBEAT).put("d", sequence ?: JSONObject.NULL))
    }

    private fun sendIdentify() {
        val id = identity ?: return
        log(GatewayLog.Level.INFO, "Identifying (bot=${id.isBot})")
        send(JSONObject().put("op", OP_IDENTIFY).put("d", id.identifyPayload(presence)))
    }

    private fun sendResume() {
        val id = identity ?: return
        log(GatewayLog.Level.INFO, "Resuming session")
        send(JSONObject().put("op", OP_RESUME).put("d", JSONObject().apply {
            put("token", id.token)
            put("session_id", sessionId)
            put("seq", sequence)
        }))
    }

    private fun sendPresenceIfChanged() {
        val current = presence ?: return
        val text = current.toString()
        if (text == sentPresence) return
        if (send(JSONObject().put("op", OP_PRESENCE_UPDATE).put("d", current))) {
            sentPresence = text
            log(GatewayLog.Level.INFO, "Presence updated")
        }
    }

    private fun send(payload: JSONObject): Boolean {
        val sent = connection?.send(payload.toString()) == true
        if (!sent) log(GatewayLog.Level.WARN, "Dropped op ${payload.optInt("op")}: socket not writable")
        return sent
    }

    private fun canResume() = sessionId != null && resumeUrl != null && sequence != null

    private fun clearSession() {
        sessionId = null
        resumeUrl = null
        sequence = null
        sentPresence = null
    }

    private fun log(level: GatewayLog.Level, message: String) = log.log(level, message)

    companion object {
        const val API_VERSION = 10

        const val OP_DISPATCH = 0
        const val OP_HEARTBEAT = 1
        const val OP_IDENTIFY = 2
        const val OP_PRESENCE_UPDATE = 3
        const val OP_RESUME = 6
        const val OP_RECONNECT = 7
        const val OP_INVALID_SESSION = 9
        const val OP_HELLO = 10
        const val OP_HEARTBEAT_ACK = 11

        const val CLOSE_NORMAL = 1000
        /** Any non-1000/1001 code keeps the session resumable. */
        const val CLOSE_RESUMABLE = 4000
        const val CLOSE_AUTH_FAILED = 4004
        const val CLOSE_INVALID_SEQ = 4007
        const val CLOSE_SESSION_TIMED_OUT = 4009

        val FATAL_CLOSE_CODES = setOf(4004, 4010, 4011, 4012, 4013, 4014)

        fun closeCodeMessage(code: Int): String = when (code) {
            4004 -> "Authentication failed (4004): the token is invalid"
            4010 -> "Invalid shard (4010)"
            4011 -> "Sharding required (4011)"
            4012 -> "Invalid API version (4012)"
            4013 -> "Invalid intents (4013)"
            4014 -> "Disallowed intents (4014)"
            else -> "Gateway closed ($code)"
        }

        private fun displayName(user: JSONObject): String? {
            val global = if (user.isNull("global_name")) null else user.optString("global_name")
            return global?.takeIf { it.isNotBlank() } ?: user.optString("username").takeIf { it.isNotBlank() }
        }
    }
}
