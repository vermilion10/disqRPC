package com.github.vermilion10.disqrpc.service

import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.github.vermilion10.disqrpc.data.local.AppDatabase
import com.github.vermilion10.disqrpc.util.ConnectionManager
import com.github.vermilion10.disqrpc.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Fallback game signal for when Usage Access is off: a notification from a whitelisted,
 * enabled game is passed to the service as a hint. Notifications from anything else are
 * ignored silently, and with Usage Access on the detector is used instead.
 */
class NotificationSensorService : NotificationListenerService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        if (pkg == packageName) return
        if (ConnectionManager.state.value != ConnectionManager.State.CONNECTED) return
        if (ForegroundAppDetector.hasUsageAccess(this)) return

        scope.launch {
            val config = AppDatabase.getDatabase(applicationContext).gameConfigDao().getConfig(pkg)
            if (config?.isEnabled != true) return@launch
            Logger.d("Notification from ${config.gameName} used as a game hint (no Usage Access)")
            runCatching {
                startService(
                    Intent(this@NotificationSensorService, RPCForegroundService::class.java)
                        .setAction(RPCForegroundService.ACTION_GAME_HINT)
                        .putExtra(RPCForegroundService.EXTRA_PACKAGE, pkg)
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
