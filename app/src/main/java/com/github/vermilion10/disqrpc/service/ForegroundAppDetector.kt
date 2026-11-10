package com.github.vermilion10.disqrpc.service

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.os.Process
import com.github.vermilion10.disqrpc.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Reports the app in the foreground using UsageStats events.
 *
 * The first poll reads the last 24 hours to find the current app; after that each poll
 * only reads events since the previous one. Without Usage Access it keeps checking for
 * the permission and starts as soon as it is granted, no reconnect needed.
 */
class ForegroundAppDetector(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onForegroundChanged: (String?) -> Unit,
    private val isProtected: suspend (String) -> Boolean = { false }
) {
    private val usm = context.getSystemService(UsageStatsManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)

    private var job: Job? = null
    @Volatile private var screenOn = true
    private var receiverRegistered = false

    @Volatile
    var foregroundPackage: String? = null
        private set

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> screenOn = false
                Intent.ACTION_SCREEN_ON -> screenOn = true
            }
        }
    }

    fun start() {
        if (job?.isActive == true) return
        screenOn = power.isInteractive
        registerScreenReceiver()
        job = scope.launch {
            val tracker = ForegroundTracker()
            var cursor = 0L
            var waitingLogged = false
            var lastOverlay: String? = null

            while (isActive) {
                if (!hasUsageAccess()) {
                    if (!waitingLogged) {
                        Logger.w("Usage Access not granted; game detection starts once it is")
                        waitingLogged = true
                    }
                    cursor = 0L
                    delay(PERMISSION_POLL_MS)
                    continue
                }
                if (waitingLogged) {
                    Logger.i("Usage Access granted, game detection started")
                    waitingLogged = false
                }

                // While the screen is off the event stream says nothing useful, and a
                // null read would clear the presence. Hold the last value.
                if (screenOn) {
                    val now = System.currentTimeMillis()
                    val begin = if (cursor == 0L) now - INITIAL_WINDOW_MS else cursor
                    if (readEvents(tracker, begin, now)) cursor = now

                    when (val decision = tracker.decide(now, isProtected)) {
                        is ForegroundTracker.Changed -> {
                            lastOverlay = null
                            foregroundPackage = decision.packageName
                            onForegroundChanged(decision.packageName)
                        }
                        is ForegroundTracker.HeldForOverlay -> if (decision.overlay != lastOverlay) {
                            lastOverlay = decision.overlay
                            Logger.d("${decision.overlay ?: "Overlay"} is above ${decision.kept}; keeping the game")
                        }
                        ForegroundTracker.Unchanged -> Unit
                    }
                }
                delay(POLL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        foregroundPackage = null
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(screenReceiver) }
            receiverRegistered = false
        }
    }

    private fun readEvents(tracker: ForegroundTracker, begin: Long, end: Long): Boolean = try {
        val events = usm.queryEvents(begin, end)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> tracker.onResumed(event.packageName)
                UsageEvents.Event.ACTIVITY_PAUSED -> tracker.onPaused(event.packageName, event.timeStamp)
            }
        }
        true
    } catch (e: SecurityException) {
        Logger.w("UsageStats permission denied: ${e.message}")
        false
    } catch (e: Exception) {
        Logger.w("UsageStats query failed: ${e.message}")
        false
    }

    private fun registerScreenReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(screenReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun hasUsageAccess(): Boolean = hasUsageAccess(context)

    companion object {
        private const val POLL_MS = 3_000L
        private const val PERMISSION_POLL_MS = 5_000L
        private const val INITIAL_WINDOW_MS = 24 * 60 * 60 * 1000L

        fun hasUsageAccess(context: Context): Boolean {
            val appOps = context.getSystemService(AppOpsManager::class.java)
            val mode = appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }
}
