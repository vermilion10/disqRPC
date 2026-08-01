package com.github.vermilion10.disqrpc.ui

import android.app.AppOpsManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.Process
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.github.vermilion10.disqrpc.data.local.AppDatabase
import com.github.vermilion10.disqrpc.data.local.GameConfig
import com.github.vermilion10.disqrpc.data.remote.PlayStoreScraper
import com.github.vermilion10.disqrpc.service.RPCForegroundService
import com.github.vermilion10.disqrpc.util.TokenManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getDatabase(application)
    private val gameConfigDao = database.gameConfigDao()
    val tokenManager = TokenManager(application)
    private val assetRegistrar = com.github.vermilion10.disqrpc.util.DiscordAssetRegistrar(application)

    val allConfigs: StateFlow<List<GameConfig>> = gameConfigDao.getAllConfigs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val logs: StateFlow<List<com.github.vermilion10.disqrpc.util.Logger.LogEntry>> = com.github.vermilion10.disqrpc.util.Logger.logs

    val connectionState = com.github.vermilion10.disqrpc.util.ConnectionManager.state
    val username = com.github.vermilion10.disqrpc.util.ConnectionManager.username
    val currentPresence = com.github.vermilion10.disqrpc.util.ConnectionManager.currentPresence

    private val _isScraping = mutableStateOf(false)
    val isScraping: State<Boolean> = _isScraping

    private val _installedApps = mutableStateOf<List<AppInfo>>(emptyList())
    val installedApps: State<List<AppInfo>> = _installedApps

    data class AppInfo(
        val name: String,
        val packageName: String
    )

    fun loadInstalledApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val pm = getApplication<Application>().packageManager
            val apps = pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
                .filter { (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 }
                .map { app ->
                    AppInfo(
                        name = pm.getApplicationLabel(app).toString(),
                        packageName = app.packageName
                    )
                }
                .sortedBy { it.name }
            
            withContext(Dispatchers.Main) {
                _installedApps.value = apps
            }
        }
    }

    fun toggleGameEnabled(config: GameConfig) {
        viewModelScope.launch {
            gameConfigDao.updateConfig(config.copy(isEnabled = !config.isEnabled))
        }
    }

    fun deleteGame(config: GameConfig) {
        viewModelScope.launch {
            gameConfigDao.deleteConfig(config)
        }
    }

    fun addGameByPackageName(packageName: String) {
        viewModelScope.launch {
            _isScraping.value = true
            val metadata = withContext(Dispatchers.IO) {
                PlayStoreScraper.fetchMetadata(packageName)
            }
            val gameName = metadata?.name ?: packageName
            val iconUrl = metadata?.iconUrl
            
            gameConfigDao.insertConfig(
                GameConfig(
                    packageName = packageName,
                    gameName = gameName,
                    customLargeImage = iconUrl,
                    isEnabled = true,
                    customDetails = "Playing $gameName"
                )
            )
            _isScraping.value = false
        }
    }

    fun saveCredentials(token: String, appId: String) {
        val cleanToken = token.trim().removeSurrounding("\"")
        
        // Log basic diagnostics about the token (safe)
        com.github.vermilion10.disqrpc.util.Logger.d("Saving token: length=${cleanToken.length}, startsWithBot=${cleanToken.startsWith("Bot ", ignoreCase = true)}")

        tokenManager.saveToken(cleanToken)
        tokenManager.saveApplicationId(appId.trim())
    }

    fun updateCustomStatus(name: String, details: String, state: String, largeImage: String, smallImage: String, startTime: Long?) {
        viewModelScope.launch {
            val appId = tokenManager.getApplicationId() ?: ""
            try {
                val publicLarge = resolveImageUrl(largeImage)
                val publicSmall = resolveImageUrl(smallImage)
                val payload = com.github.vermilion10.disqrpc.util.PresencePayloadBuilder.buildActivityPayload(
                    name = if (name.isNotBlank()) name else "Custom RPC",
                    appId = appId,
                    details = details.ifBlank { null },
                    state = state.ifBlank { null },
                    largeImage = publicLarge,
                    smallImage = publicSmall,
                    startTime = startTime
                )

                com.github.vermilion10.disqrpc.util.Logger.d("Built Payload (ms TS): $payload")

                val intent = Intent(getApplication(), RPCForegroundService::class.java).apply {
                    action = RPCForegroundService.ACTION_UPDATE_PRESENCE
                    putExtra(RPCForegroundService.EXTRA_PAYLOAD, payload)
                }
                getApplication<Application>().startService(intent)
            } catch (e: Exception) {
                com.github.vermilion10.disqrpc.util.Logger.e("Error building presence: ${e.message}")
            }
        }
    }

    private suspend fun resolveImageUrl(key: String): String {
        if (key.isBlank()) return ""
        return withContext(Dispatchers.IO) {
            com.github.vermilion10.disqrpc.util.AssetResolver.resolve(getApplication(), assetRegistrar, key) ?: ""
        }
    }

    fun clearStatus() {
        val payload = """
            {
                "status": "online",
                "since": 0,
                "activities": [],
                "afk": false
            }
        """.trimIndent()

        val intent = Intent(getApplication(), RPCForegroundService::class.java).apply {
            action = RPCForegroundService.ACTION_UPDATE_PRESENCE
            putExtra(RPCForegroundService.EXTRA_PAYLOAD, payload)
        }
        getApplication<Application>().startService(intent)
    }

    fun connect() {
        val intent = Intent(getApplication(), RPCForegroundService::class.java).apply {
            action = RPCForegroundService.ACTION_CONNECT
        }
        getApplication<Application>().startService(intent)
    }

    fun disconnect() {
        val intent = Intent(getApplication(), RPCForegroundService::class.java).apply {
            action = RPCForegroundService.ACTION_DISCONNECT
        }
        getApplication<Application>().startService(intent)
    }

    fun clearLogs() {
        com.github.vermilion10.disqrpc.util.Logger.clearLogs()
    }

    fun isBatteryOptimizationIgnored(): Boolean {
        val pm = getApplication<Application>().getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(getApplication<Application>().packageName)
    }

    fun isUsageAccessGranted(): Boolean {
        val appOps = getApplication<Application>().getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                getApplication<Application>().packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                getApplication<Application>().packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
