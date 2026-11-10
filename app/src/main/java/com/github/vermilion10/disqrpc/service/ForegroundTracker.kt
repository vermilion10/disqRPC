package com.github.vermilion10.disqrpc.service

/**
 * Pure foreground-app bookkeeping fed with usage events in time order.
 *
 * [rawTop] is whatever app was resumed last and not paused since. [decide] turns that
 * into the app we report, with one guard: an overlay (autoclicker, floating window,
 * transparent activity) resumed on top of a protected game does not replace it as long
 * as the game itself was never paused.
 */
class ForegroundTracker {

    var rawTop: String? = null
        private set

    var reported: String? = null
        private set

    private var reportedSince = 0L
    private var reportedPaused = false

    fun onResumed(packageName: String) {
        rawTop = packageName
    }

    fun onPaused(packageName: String, timestamp: Long) {
        if (packageName == rawTop) rawTop = null
        if (packageName == reported && timestamp >= reportedSince) reportedPaused = true
    }

    /**
     * Returns the new package to report when it changed, or [Unchanged].
     * [isProtected] says whether a package is a game worth holding on to.
     */
    suspend fun decide(now: Long, isProtected: suspend (String) -> Boolean): Decision {
        val top = rawTop
        if (top == reported) return Unchanged
        val current = reported
        if (current != null && !reportedPaused && isProtected(current)) return HeldForOverlay(current, top)
        reported = top
        reportedSince = now
        reportedPaused = false
        return Changed(top)
    }

    sealed interface Decision
    data object Unchanged : Decision
    data class Changed(val packageName: String?) : Decision
    data class HeldForOverlay(val kept: String, val overlay: String?) : Decision
}
