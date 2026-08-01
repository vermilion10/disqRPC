package com.github.vermilion10.disqrpc.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.github.vermilion10.disqrpc.data.local.AppDatabase
import com.github.vermilion10.disqrpc.data.local.GameConfig
import com.github.vermilion10.disqrpc.data.remote.PlayStoreScraper
import com.github.vermilion10.disqrpc.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WhitelistReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_ADD_GAME = "com.github.vermilion10.disqrpc.ADD_GAME"
        const val EXTRA_PACKAGE = "extra_package"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ADD_GAME) return
        val packageName = intent.getStringExtra(EXTRA_PACKAGE) ?: return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.getDatabase(context.applicationContext).gameConfigDao()
                if (dao.getConfig(packageName) == null) {
                    val metadata = PlayStoreScraper.fetchMetadata(packageName)
                    val gameName = metadata?.name ?: runCatching {
                        context.packageManager.getApplicationLabel(
                            context.packageManager.getApplicationInfo(packageName, 0)
                        ).toString()
                    }.getOrElse { packageName }
                    dao.insertConfig(
                        GameConfig(
                            packageName = packageName,
                            gameName = gameName,
                            customLargeImage = metadata?.iconUrl,
                            isEnabled = true,
                            customDetails = "Playing $gameName"
                        )
                    )
                    Logger.i("Whitelisted game: $gameName ($packageName)")
                }
                val serviceIntent = Intent(context, RPCForegroundService::class.java).apply {
                    action = RPCForegroundService.ACTION_APP_WHITELISTED
                    putExtra(RPCForegroundService.EXTRA_PACKAGE, packageName)
                }
                runCatching { context.startService(serviceIntent) }
            } catch (e: Exception) {
                Logger.e("Failed to whitelist $packageName: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
