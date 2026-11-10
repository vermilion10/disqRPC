package com.github.vermilion10.disqrpc.presence

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** What the engine needs to know about installed apps and their saved configs. */
interface GameSource {
    enum class Kind {
        /** Whitelisted and enabled. */
        GAME,
        /** Whitelisted but switched off. */
        DISABLED,
        /** Not whitelisted; the user may want to add it. */
        UNKNOWN,
        /** Launcher, system UI, this app: never a game, never prompted. */
        IGNORED
    }

    suspend fun classify(packageName: String): Kind

    /** Full presence object (status, activities...) for an enabled game, or null. */
    suspend fun presenceFor(packageName: String, startedAt: Long): JSONObject?
}

/**
 * Decides what presence to show and sends it only when it changes.
 *
 * Priority: a custom status set by the user, then the game in the foreground, then an
 * empty presence. Leaving a game starts a grace period; coming back within it keeps the
 * game's original start time, so a quick trip to another app does not reset the timer.
 *
 * Like the gateway client, all state lives in one event-loop coroutine.
 */
class PresenceEngine(
    private val scope: CoroutineScope,
    private val source: GameSource,
    private val sink: (JSONObject) -> Unit,
    private val onUnknownApp: (String) -> Unit = {},
    private val log: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val graceMs: Long = 30_000
) {

    private sealed interface Event {
        data class Foreground(val packageName: String?) : Event
        data class Custom(val presence: JSONObject?) : Event
        data object Refresh : Event
        data object Reset : Event
        data class GraceOver(val token: Int) : Event
    }

    private val events = Channel<Event>(Channel.UNLIMITED)

    private var foreground: String? = null
    private var activeGame: String? = null
    private var gameStartedAt = 0L
    private var custom: JSONObject? = null
    private var graceJob: Job? = null
    private var graceToken = 0
    private var lastSent: String? = null
    private var lastStatus = "online"

    init {
        scope.launch { for (event in events) handle(event) }
    }

    fun onForegroundChanged(packageName: String?) { events.trySend(Event.Foreground(packageName)) }

    /** Sets (or with null, clears) a custom status that overrides game presence. */
    fun setCustomPresence(presence: JSONObject?) { events.trySend(Event.Custom(presence)) }

    /** Re-reads game configs, e.g. after a game was added, edited, toggled or removed. */
    fun refresh() { events.trySend(Event.Refresh) }

    /** Forgets everything; used when the session ends. */
    fun reset() { events.trySend(Event.Reset) }

    private suspend fun handle(event: Event) {
        when (event) {
            is Event.Foreground -> {
                foreground = event.packageName
                evaluateForeground(prompt = true)
            }
            is Event.Custom -> {
                custom = event.presence
                log(if (event.presence == null) "Custom status cleared" else "Custom status set")
                publish()
            }
            Event.Refresh -> {
                // The active game may have been disabled or deleted, or the foreground
                // app may just have been whitelisted.
                val active = activeGame
                if (active != null && source.classify(active) != GameSource.Kind.GAME) {
                    cancelGrace()
                    activeGame = null
                }
                evaluateForeground(prompt = false)
                publish()
            }
            Event.Reset -> {
                cancelGrace()
                foreground = null
                activeGame = null
                custom = null
                lastSent = null
                lastStatus = "online"
            }
            is Event.GraceOver -> if (event.token == graceToken && graceJob != null) {
                graceJob = null
                log("Left ${activeGame ?: "game"} for ${graceMs / 1000}s, clearing game presence")
                activeGame = null
                publish()
            }
        }
    }

    private suspend fun evaluateForeground(prompt: Boolean) {
        val pkg = foreground
        val kind = pkg?.let { source.classify(it) }
        if (pkg != null && kind == GameSource.Kind.GAME) {
            cancelGrace()
            if (pkg != activeGame) {
                activeGame = pkg
                gameStartedAt = clock()
                log("Game in foreground: $pkg")
                publish()
            }
            return
        }
        if (prompt && pkg != null && kind == GameSource.Kind.UNKNOWN) onUnknownApp(pkg)
        if (activeGame != null && graceJob == null) startGrace()
    }

    private fun startGrace() {
        val token = ++graceToken
        graceJob = scope.launch {
            delay(graceMs)
            events.send(Event.GraceOver(token))
        }
    }

    private fun cancelGrace() {
        graceJob?.cancel()
        graceJob = null
        graceToken++
    }

    private suspend fun publish() {
        val desired = custom
            ?: activeGame?.let { source.presenceFor(it, gameStartedAt) }
            ?: emptyPresence(lastStatus)
        val text = desired.toString()
        if (text == lastSent) return
        // Nothing has been shown yet, so there is nothing to clear.
        if (lastSent == null && isEmpty(desired)) return
        lastSent = text
        lastStatus = desired.optString("status", lastStatus).ifBlank { "online" }
        sink(desired)
    }

    companion object {
        fun emptyPresence(status: String = "online"): JSONObject = JSONObject()
            .put("status", status)
            .put("since", 0)
            .put("activities", JSONArray())
            .put("afk", false)

        private fun isEmpty(presence: JSONObject) =
            (presence.optJSONArray("activities")?.length() ?: 0) == 0
    }
}
