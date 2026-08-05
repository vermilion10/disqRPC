package com.github.vermilion10.disqrpc.service

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Process
import com.github.vermilion10.disqrpc.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ForegroundAppDetector(
    private val context: Context,
    private val onForegroundChanged: (String?) -> Unit,
    private val isProtected: suspend (String) -> Boolean = { false }
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var currentPackage: String? = null
    @Volatile
    private var currentPackageSince = 0L
    @Volatile
    private var currentPackageStillOnTop = false
    @Volatile
    private var running = false
    @Volatile
    private var screenOn = true
    private var pollJob: Job? = null

    val foregroundPackage: String?
        get() = currentPackage

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> screenOn = false
                Intent.ACTION_SCREEN_ON -> screenOn = true
            }
        }
    }

    fun start() {
        if (running) return
        if (!hasUsageAccess()) {
            Logger.w("Foreground detection skipped: Usage Access not granted.")
            return
        }
        running = true
        currentPackage = null
        currentPackageSince = 0L
        currentPackageStillOnTop = false
        registerScreenReceiver()
        pollJob = scope.launch {
            while (isActive && running) {
                // While the display is off the polled package is unreliable
                // (and a null read would clear presence). Hold the last value.
                if (screenOn) {
                    val pkg = queryForegroundPackage()
                    if (pkg != currentPackage) {
                        // Overlay guard: windows drawn over an active, still-running
                        // whitelisted game (autoclickers, floating windows, transparent
                        // activities) register as the new "foreground". If the current
                        // package was never backgrounded it is still the real top app,
                        // so keep reporting it and let the game presence stand.
                        val current = currentPackage
                        val keep = current != null &&
                            currentPackageStillOnTop &&
                            isProtected(current)
                        if (keep) {
                            Logger.d("Overlay detected above $currentPackage; keeping it.")
                        } else {
                            currentPackage = pkg
                            currentPackageSince = System.currentTimeMillis()
                            currentPackageStillOnTop = true
                            onForegroundChanged(pkg)
                        }
                    }
                }
                delay(3000)
            }
        }
    }

    fun stop() {
        running = false
        pollJob?.cancel()
        pollJob = null
        if (screenReceiverRegistered) {
            context.unregisterReceiver(screenReceiver)
            screenReceiverRegistered = false
        }
    }

    private var screenReceiverRegistered = false

    private fun registerScreenReceiver() {
        if (screenReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(screenReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(screenReceiver, filter)
        }
        screenReceiverRegistered = true
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun queryForegroundPackage(): String? {
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val endTime = System.currentTimeMillis()
            // Use a long window (24h): usage events only fire on transitions, so a game
            // played continuously would slide out of a short window and read as "no app".
            val beginTime = endTime - 24 * 60 * 60 * 1000L
            val events = usm.queryEvents(beginTime, endTime)
            var foreground: String? = null
            var foregroundTime = 0L
            var previousStillOnTop = (currentPackage == null || currentPackageSince <= 0L)
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                when (event.eventType) {
                    UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                        foreground = event.packageName
                        foregroundTime = event.timeStamp
                    }
                    UsageEvents.Event.MOVE_TO_BACKGROUND ->
                        if (event.packageName == foreground) foreground = null
                }
                // Did the package we currently report ever get pushed to the background
                // after it came to the foreground? If not, an overlay may be on top of it.
                if (event.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND &&
                    event.packageName == currentPackage &&
                    event.timeStamp >= currentPackageSince
                ) {
                    previousStillOnTop = false
                }
            }
            currentPackageStillOnTop = previousStillOnTop
            foreground
        } catch (e: SecurityException) {
            Logger.w("UsageStats permission denied: ${e.message}")
            currentPackageStillOnTop = false
            null
        } catch (e: Exception) {
            currentPackageStillOnTop = false
            null
        }
    }
}
