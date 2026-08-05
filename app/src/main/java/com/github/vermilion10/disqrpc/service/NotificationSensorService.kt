package com.github.vermilion10.disqrpc.service

import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.github.vermilion10.disqrpc.data.local.AppDatabase
import com.github.vermilion10.disqrpc.data.local.GameConfig
import com.github.vermilion10.disqrpc.util.Logger
import com.github.vermilion10.disqrpc.util.PresencePayloadBuilder
import com.github.vermilion10.disqrpc.util.TokenManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class NotificationSensorService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val assetRegistrar by lazy { com.github.vermilion10.disqrpc.util.DiscordAssetRegistrar(applicationContext) }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val packageName = sbn?.packageName ?: return
        if (packageName == "android" || packageName == "com.android.systemui") return

        Logger.d("Notification posted from: $packageName")
        serviceScope.launch {
            val db = AppDatabase.getDatabase(applicationContext)
            var config = db.gameConfigDao().getConfig(packageName)

            // Temporary test override for ADB shell notifications
            if (packageName == "com.android.shell") {
                config = GameConfig(
                    packageName = "com.android.shell",
                    gameName = "ADB Test Game",
                    isEnabled = true,
                    customDetails = "Testing via ADB"
                )
            }

            if (config != null && config.isEnabled) {
                val appId = TokenManager(applicationContext).getApplicationId() ?: ""
                val largeImage = com.github.vermilion10.disqrpc.util.AssetResolver.resolve(
                    applicationContext, assetRegistrar, config.customLargeImage
                )
                val smallImage = com.github.vermilion10.disqrpc.util.AssetResolver.resolve(
                    applicationContext, assetRegistrar, config.customSmallImage
                )
                val payload = PresencePayloadBuilder.buildActivityPayload(
                    name = config.gameName,
                    appId = appId,
                    details = config.customDetails ?: "Playing ${config.gameName}",
                    state = config.customState,
                    largeImage = largeImage,
                    smallImage = smallImage,
                    startTime = System.currentTimeMillis(),
                    status = config.status
                )
                Logger.d("Built Payload: $payload")

                val intent = Intent(this@NotificationSensorService, RPCForegroundService::class.java).apply {
                    action = RPCForegroundService.ACTION_UPDATE_PRESENCE
                    putExtra(RPCForegroundService.EXTRA_PAYLOAD, payload)
                }
                startService(intent)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
