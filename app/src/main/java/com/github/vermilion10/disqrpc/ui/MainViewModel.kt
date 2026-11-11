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
import android.graphics.drawable.Drawable
import androidx.core.app.NotificationManagerCompat
import com.github.vermilion10.disqrpc.data.catalog.OfficialGame
import com.github.vermilion10.disqrpc.data.catalog.DetectableCatalog
import com.github.vermilion10.disqrpc.data.catalog.DetectableGame
import com.github.vermilion10.disqrpc.data.local.StatusPreset
import com.github.vermilion10.disqrpc.presence.CustomStatusForm
import com.github.vermilion10.disqrpc.util.ActivitySpec
import com.github.vermilion10.disqrpc.util.PresencePayloadBuilder
import com.github.vermilion10.disqrpc.gateway.GatewayIdentity
import com.github.vermilion10.disqrpc.util.AppSettings
import kotlinx.coroutines.flow.map
import com.github.vermilion10.disqrpc.data.local.GameConfigFactory
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

    private val appSettings = AppSettings(application)
    private val catalog = DetectableCatalog.get(application)

    /** Saved games shown under a Discord application (matched or linked), keyed by package. */
    val catalogMatches: StateFlow<Map<String, OfficialGame>> = gameConfigDao.getAllConfigs()
        .map { configs -> configs.mapNotNull { c -> catalog.officialFor(c)?.let { c.packageName to it } }.toMap() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val logs: StateFlow<List<com.github.vermilion10.disqrpc.util.Logger.LogEntry>> = com.github.vermilion10.disqrpc.util.Logger.logs

    val connectionState = com.github.vermilion10.disqrpc.util.ConnectionManager.state
    val username = com.github.vermilion10.disqrpc.util.ConnectionManager.username
    val currentPresence = com.github.vermilion10.disqrpc.util.ConnectionManager.currentPresence
    val failureReason = com.github.vermilion10.disqrpc.util.ConnectionManager.failureReason

    fun hasToken(): Boolean = !tokenManager.getToken().isNullOrBlank()

    private val _isAddingGame = mutableStateOf(false)
    val isAddingGame: State<Boolean> = _isAddingGame

    init {
        viewModelScope.launch { catalog.refreshIfStale() }
    }

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

    fun updateGameConfig(config: GameConfig) {
        viewModelScope.launch {
            gameConfigDao.updateConfig(config)
        }
    }

    fun deleteGame(config: GameConfig) {
        viewModelScope.launch {
            gameConfigDao.deleteConfig(config)
        }
    }

    fun addGameByPackageName(packageName: String) {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return
        viewModelScope.launch {
            _isAddingGame.value = true
            try {
                // Re-adding a package must not wipe the user's customisations.
                if (gameConfigDao.getConfig(pkg) == null) {
                    gameConfigDao.insertConfig(GameConfigFactory.create(getApplication(), pkg))
                }
            } finally {
                _isAddingGame.value = false
            }
        }
    }

    /** Saves a user token, or returns why it can't be used (nothing is saved then). */
    fun saveToken(token: String): String? {
        val cleanToken = token.trim().removeSurrounding("\"")
        GatewayIdentity.problemWith(cleanToken)?.let { return it }
        tokenManager.saveToken(cleanToken)
        return null
    }

    fun saveApplicationId(appId: String) = tokenManager.saveApplicationId(appId.trim())

    /** Disconnects and forgets the token. */
    fun logOut() {
        disconnect()
        tokenManager.clearToken()
    }

    var allowImageUploads: Boolean
        get() = appSettings.allowImageUploads
        set(value) { appSettings.allowImageUploads = value }

    fun areNotificationsEnabled(): Boolean =
        NotificationManagerCompat.from(getApplication<Application>()).areNotificationsEnabled()

    fun isNotificationListenerEnabled(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(getApplication<Application>())
            .contains(getApplication<Application>().packageName)

    /** The installed app's launcher icon, for the game list. */
    fun appIcon(packageName: String): Drawable? = runCatching {
        getApplication<Application>().packageManager.getApplicationIcon(packageName)
    }.getOrNull()

    /** The editable custom-status form, kept here so it survives tab switches. */
    private val _customForm = mutableStateOf(CustomStatusForm())
    val customForm: State<CustomStatusForm> = _customForm

    fun updateForm(form: CustomStatusForm) {
        _customForm.value = form
    }

    private val presetDao = database.statusPresetDao()
    private val gson = com.google.gson.Gson()

    val presets: StateFlow<List<StatusPreset>> = presetDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Saves the current form as a named preset. */
    fun saveCurrentAsPreset(name: String) {
        val json = gson.toJson(_customForm.value)
        viewModelScope.launch { presetDao.upsert(StatusPreset(name = name.trim(), formJson = json)) }
    }

    /** Loads a preset into the form; no effect if it can't be read. */
    fun loadPreset(preset: StatusPreset) {
        runCatching { gson.fromJson(preset.formJson, CustomStatusForm::class.java) }
            .getOrNull()?.let { _customForm.value = it }
    }

    fun deletePreset(preset: StatusPreset) {
        viewModelScope.launch { presetDao.delete(preset) }
    }

    /** Searches every game in Discord's detectable list by name. */
    suspend fun searchOfficialGames(query: String): List<DetectableGame> = catalog.searchGames(query)

    /** Builds the full presence from [form], resolving images, then sends it to the service. */
    fun applyCustomStatus(form: CustomStatusForm) {
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val (start, end) = form.resolveTimestamps(now, startOfToday())
                val appId = form.officialAppId ?: tokenManager.getApplicationId().orEmpty()
                val large = resolveImageUrl(form.largeImage.ifBlank { form.officialIconUrl.orEmpty() })
                val spec = ActivitySpec(
                    name = form.officialName ?: form.name,
                    appId = appId,
                    type = form.type,
                    statusDisplay = form.statusDisplay,
                    details = form.details.ifBlank { null },
                    detailsUrl = form.detailsUrl.ifBlank { null },
                    state = form.state.ifBlank { null },
                    stateUrl = form.stateUrl.ifBlank { null },
                    largeImage = large.ifBlank { null },
                    largeText = form.largeText.ifBlank { null },
                    largeUrl = form.largeUrl.ifBlank { null },
                    smallImage = resolveImageUrl(form.smallImage).ifBlank { null },
                    smallText = form.smallText.ifBlank { null },
                    startTime = start,
                    endTime = end,
                    partySize = if (form.partyEnabled) form.partySize else null,
                    partyMax = if (form.partyEnabled) form.partyMax else null,
                    buttons = form.buttons,
                    accountStatus = form.accountStatus
                )
                val payload = PresencePayloadBuilder.build(spec).toString()
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

    private fun startOfToday(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private suspend fun resolveImageUrl(key: String): String {
        if (key.isBlank()) return ""
        return withContext(Dispatchers.IO) {
            com.github.vermilion10.disqrpc.util.AssetResolver.resolve(getApplication(), assetRegistrar, key) ?: ""
        }
    }

    /** Drops the custom status; game presence (if a game is open) takes over again. */
    fun clearStatus() {
        val intent = Intent(getApplication(), RPCForegroundService::class.java).apply {
            action = RPCForegroundService.ACTION_CLEAR_PRESENCE
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
