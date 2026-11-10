package com.github.vermilion10.disqrpc.presence

import com.github.vermilion10.disqrpc.presence.GameSource.Kind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PresenceEngineTest {

    private class FakeSource : GameSource {
        val kinds = mutableMapOf<String, Kind>()
        override suspend fun classify(packageName: String) = kinds[packageName] ?: Kind.UNKNOWN
        override suspend fun presenceFor(packageName: String, startedAt: Long): JSONObject? =
            if (kinds[packageName] == Kind.GAME) game(packageName, startedAt) else null
    }

    private val source = FakeSource().apply {
        kinds["game.a"] = Kind.GAME
        kinds["game.b"] = Kind.GAME
        kinds["launcher"] = Kind.IGNORED
    }
    private val sent = mutableListOf<JSONObject>()
    private val prompted = mutableListOf<String>()

    private fun TestScope.engine() = PresenceEngine(
        scope = backgroundScope,
        source = source,
        sink = { sent += it },
        onUnknownApp = { prompted += it },
        clock = { currentTime },
        graceMs = 30_000
    )

    private fun JSONObject.activityName(): String? =
        optJSONArray("activities")?.optJSONObject(0)?.optString("name")

    private fun JSONObject.start(): Long =
        getJSONArray("activities").getJSONObject(0).getJSONObject("timestamps").getLong("start")

    @Test
    fun gameInForegroundIsSentOnce() = runTest {
        val engine = engine()
        engine.onForegroundChanged("game.a")
        engine.onForegroundChanged("game.a")
        runCurrent()
        assertEquals(1, sent.size)
        assertEquals("game.a", sent[0].activityName())
    }

    @Test
    fun leavingAGameClearsOnlyAfterGracePeriod() = runTest {
        val engine = engine()
        engine.onForegroundChanged("game.a")
        engine.onForegroundChanged("launcher")
        runCurrent()
        advanceTimeBy(29_000)
        runCurrent()
        assertEquals(1, sent.size)

        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(2, sent.size)
        assertEquals(0, sent[1].getJSONArray("activities").length())
    }

    @Test
    fun returningWithinGraceKeepsStartTime() = runTest {
        val engine = engine()
        engine.onForegroundChanged("game.a")
        runCurrent()
        val start = sent.single().start()

        advanceTimeBy(5_000)
        engine.onForegroundChanged("launcher")
        advanceTimeBy(10_000)
        engine.onForegroundChanged("game.a")
        advanceTimeBy(60_000)
        runCurrent()

        // No clear, no resend, timer untouched.
        assertEquals(1, sent.size)
        assertEquals(start, sent.single().start())
    }

    @Test
    fun noClearIsSentWhenNothingWasShown() = runTest {
        val engine = engine()
        engine.onForegroundChanged("some.app")
        engine.onForegroundChanged(null)
        advanceTimeBy(120_000)
        runCurrent()
        assertTrue(sent.isEmpty())
        assertEquals(listOf("some.app"), prompted)
    }

    @Test
    fun ignoredAppsAreNeverPrompted() = runTest {
        val engine = engine()
        engine.onForegroundChanged("launcher")
        runCurrent()
        assertTrue(prompted.isEmpty())
    }

    @Test
    fun switchingGamesSendsNewGameImmediately() = runTest {
        val engine = engine()
        engine.onForegroundChanged("game.a")
        advanceTimeBy(1_000)
        engine.onForegroundChanged("game.b")
        runCurrent()
        assertEquals(listOf("game.a", "game.b"), sent.map { it.activityName() })
    }

    @Test
    fun customStatusOverridesGamesUntilCleared() = runTest {
        val engine = engine()
        engine.onForegroundChanged("game.a")
        runCurrent()
        engine.setCustomPresence(custom("Studying"))
        engine.onForegroundChanged("game.b")
        runCurrent()
        assertEquals(listOf("game.a", "Studying"), sent.map { it.activityName() })

        engine.setCustomPresence(null)
        runCurrent()
        assertEquals("game.b", sent.last().activityName())
    }

    @Test
    fun whitelistingTheForegroundAppStartsItsPresence() = runTest {
        val engine = engine()
        engine.onForegroundChanged("new.game")
        runCurrent()
        assertTrue(sent.isEmpty())

        source.kinds["new.game"] = Kind.GAME
        engine.refresh()
        runCurrent()
        assertEquals("new.game", sent.single().activityName())
        // A refresh must not re-prompt.
        assertEquals(listOf("new.game"), prompted)
    }

    @Test
    fun disablingTheActiveGameClearsImmediately() = runTest {
        val engine = engine()
        engine.onForegroundChanged("game.a")
        runCurrent()

        source.kinds["game.a"] = Kind.DISABLED
        engine.refresh()
        runCurrent()
        assertEquals(2, sent.size)
        assertEquals(0, sent[1].getJSONArray("activities").length())
    }

    @Test
    fun clearKeepsTheLastStatus() = runTest {
        val engine = engine()
        engine.setCustomPresence(custom("Busy", status = "dnd"))
        runCurrent()
        engine.setCustomPresence(null)
        runCurrent()
        assertEquals("dnd", sent.last().getString("status"))
        assertEquals(0, sent.last().getJSONArray("activities").length())
    }

    @Test
    fun resetForgetsWhatWasSent() = runTest {
        val engine = engine()
        engine.onForegroundChanged("game.a")
        runCurrent()
        engine.reset()
        engine.onForegroundChanged("game.a")
        runCurrent()
        // After a new session the same presence has to be sent again.
        assertEquals(2, sent.size)
    }

    private companion object {
        fun game(name: String, start: Long) = JSONObject()
            .put("status", "online")
            .put("activities", JSONArray().put(
                JSONObject().put("name", name).put("type", 0)
                    .put("timestamps", JSONObject().put("start", start))
            ))

        fun custom(name: String, status: String = "online") = JSONObject()
            .put("status", status)
            .put("activities", JSONArray().put(JSONObject().put("name", name).put("type", 0)))
    }
}
