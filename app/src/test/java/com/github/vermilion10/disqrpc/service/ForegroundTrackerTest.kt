package com.github.vermilion10.disqrpc.service

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundTrackerTest {

    private val games = setOf("game")
    private val isProtected: suspend (String) -> Boolean = { it in games }

    @Test
    fun reportsResumedAppAndNullAfterPause() = runTest {
        val t = ForegroundTracker()
        t.onResumed("app")
        assertEquals(ForegroundTracker.Changed("app"), t.decide(1, isProtected))
        assertEquals(ForegroundTracker.Unchanged, t.decide(2, isProtected))
        t.onPaused("app", 3)
        assertEquals(ForegroundTracker.Changed(null), t.decide(4, isProtected))
    }

    @Test
    fun overlayAboveARunningGameKeepsTheGame() = runTest {
        val t = ForegroundTracker()
        t.onResumed("game")
        t.decide(10, isProtected)

        // Overlay resumes on top, the game is never paused.
        t.onResumed("autoclicker")
        assertEquals(ForegroundTracker.HeldForOverlay("game", "autoclicker"), t.decide(20, isProtected))
        assertEquals("game", t.reported)
    }

    @Test
    fun gamePausedThenOtherAppReportsTheOtherApp() = runTest {
        val t = ForegroundTracker()
        t.onResumed("game")
        t.decide(10, isProtected)

        t.onPaused("game", 15)
        t.onResumed("browser")
        assertEquals(ForegroundTracker.Changed("browser"), t.decide(20, isProtected))
    }

    @Test
    fun nonGamesAreNotHeldAgainstOverlays() = runTest {
        val t = ForegroundTracker()
        t.onResumed("browser")
        t.decide(10, isProtected)
        t.onResumed("chat")
        assertEquals(ForegroundTracker.Changed("chat"), t.decide(20, isProtected))
    }

    @Test
    fun pauseOfAnOlderAppDoesNotClearTheTopApp() = runTest {
        val t = ForegroundTracker()
        t.onResumed("a")
        t.onResumed("b")
        t.onPaused("a", 5)
        assertEquals(ForegroundTracker.Changed("b"), t.decide(10, isProtected))
    }
}
