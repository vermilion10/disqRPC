package com.github.vermilion10.disqrpc.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.github.vermilion10.disqrpc.R
import com.github.vermilion10.disqrpc.data.catalog.DetectableCatalog
import com.github.vermilion10.disqrpc.data.local.AppDatabase
import com.github.vermilion10.disqrpc.data.local.GameConfig
import com.github.vermilion10.disqrpc.gateway.GatewayClient
import com.github.vermilion10.disqrpc.gateway.GatewayIdentity
import com.github.vermilion10.disqrpc.gateway.GatewayLog
import com.github.vermilion10.disqrpc.gateway.GatewayState
import com.github.vermilion10.disqrpc.gateway.OkHttpGatewayTransport
import com.github.vermilion10.disqrpc.util.ConnectionManager
import com.github.vermilion10.disqrpc.util.Logger
import com.github.vermilion10.disqrpc.util.PresencePayloadBuilder
import com.github.vermilion10.disqrpc.util.TokenManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

class RPCForegroundService : Service() {

    private lateinit var tokenManager: TokenManager
    private lateinit var gateway: GatewayClient

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val detector = ForegroundAppDetector(
        this,
        { pkg -> onForegroundChanged(pkg) },
        isProtected = { pkg ->
            AppDatabase.getDatabase(this).gameConfigDao().getConfig(pkg)?.isEnabled == true
        }
    )
    private val promptedPackages = HashSet<String>()
    @Volatile
    private var activeGamePackage: String? = null
    @Volatile
    private var lastPresenceStatus: String = "online"
    private var clearJob: Job? = null
    private var launcherPackage: String? = null
    @Volatile
    private var stopWhenDisconnected = false
    private val assetRegistrar by lazy { com.github.vermilion10.disqrpc.util.DiscordAssetRegistrar(this) }

    override fun onCreate() {
        super.onCreate()
        tokenManager = TokenManager(this)
        gateway = GatewayClient(
            transport = OkHttpGatewayTransport(),
            identityProvider = { GatewayIdentity.fromToken(tokenManager.getToken()) },
            scope = serviceScope,
            log = { level, message ->
                when (level) {
                    GatewayLog.Level.DEBUG -> Logger.d(message)
                    GatewayLog.Level.INFO -> Logger.i(message)
                    GatewayLog.Level.WARN -> Logger.w(message)
                    GatewayLog.Level.ERROR -> Logger.e(message)
                }
            }
        )

        createNotificationChannel()
        createPromptChannel()
        launcherPackage = resolveHomeLauncher()
        serviceScope.launch { gateway.state.collect { onGatewayState(it) } }
        registerNetworkCallback()
        serviceScope.launch { DetectableCatalog.get(this@RPCForegroundService).refreshIfStale() }
    }

    /**
     * Tracks physical networks only. The default-network callback is useless here: an
     * always-on VPN (Tailscale, WireGuard...) stays "connected and validated" as the app's
     * default network even when Wi-Fi and mobile data are both gone. NetworkRequest.Builder
     * adds NOT_VPN by default, so this request only matches real networks.
     */
    private val physicalNetworks = java.util.Collections.synchronizedSet(HashSet<Network>())

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            physicalNetworks.add(network)
            gateway.setNetworkAvailable(true)
        }

        override fun onLost(network: Network) {
            physicalNetworks.remove(network)
            gateway.setNetworkAvailable(physicalNetworks.isNotEmpty())
        }
    }

    private fun registerNetworkCallback() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        getSystemService(ConnectivityManager::class.java).registerNetworkCallback(request, networkCallback)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification(statusText(gateway.state.value)))

        when (intent?.action) {
            ACTION_CONNECT -> {
                stopWhenDisconnected = false
                gateway.connect()
            }
            ACTION_DISCONNECT -> disconnect()
            ACTION_UPDATE_PRESENCE -> {
                val payload = intent.getStringExtra(EXTRA_PAYLOAD)
                payload?.let { updatePresence(it) }
            }
            ACTION_APP_WHITELISTED -> {
                val pkg = intent.getStringExtra(EXTRA_PACKAGE)
                pkg?.let { onAppWhitelisted(it) }
            }
        }

        return START_STICKY
    }

    private fun onGatewayState(state: GatewayState) {
        when (state) {
            is GatewayState.Ready -> {
                ConnectionManager.setUsername(state.username)
                ConnectionManager.updateState(ConnectionManager.State.CONNECTED)
                startDetector()
            }
            is GatewayState.Connecting ->
                ConnectionManager.updateState(ConnectionManager.State.CONNECTING)
            is GatewayState.Failed -> {
                ConnectionManager.updateState(ConnectionManager.State.FAILED)
                shutDown()
            }
            GatewayState.Disconnected -> {
                ConnectionManager.updateState(ConnectionManager.State.DISCONNECTED)
                if (stopWhenDisconnected) shutDown()
            }
        }
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, createNotification(statusText(state)))
    }

    private fun statusText(state: GatewayState): String = when (state) {
        is GatewayState.Ready -> "Connected as ${state.username}"
        is GatewayState.Connecting ->
            if (state.attempt == 0) "Connecting..." else "Reconnecting (attempt ${state.attempt})..."
        is GatewayState.Failed -> "Failed: ${state.reason}"
        GatewayState.Disconnected -> "Disconnected"
    }

    private fun updatePresence(payload: String) {
        val json = try {
            JSONObject(payload)
        } catch (e: Exception) {
            Logger.e("Invalid presence payload: ${e.message}")
            return
        }
        Logger.d("Presence payload: $payload")
        lastPresenceStatus = json.optString("status", "online")
        ConnectionManager.setCurrentPresence(payload)
        gateway.updatePresence(json)
    }

    private fun disconnect() {
        stopWhenDisconnected = true
        clearJob?.cancel()
        stopDetector()
        // The service stops once the gateway reports Disconnected, so the close frame
        // is sent before the scope is cancelled.
        gateway.disconnect()
    }

    private fun shutDown() {
        clearJob?.cancel()
        stopDetector()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startDetector() {
        detector.start()
    }

    private fun stopDetector() {
        detector.stop()
        activeGamePackage = null
    }

    private fun onForegroundChanged(packageName: String?) {
        clearJob?.cancel()
        if (packageName == null) {
            scheduleClearPresence()
            return
        }
        if (packageName == activeGamePackage) return
        if (isIgnoredPackage(packageName)) {
            scheduleClearPresence()
            return
        }
        serviceScope.launch {
            val config = AppDatabase.getDatabase(this@RPCForegroundService)
                .gameConfigDao()
                .getConfig(packageName)
            when {
                config?.isEnabled == true -> {
                    activeGamePackage = packageName
                    sendGamePresence(config)
                }
                config == null -> {
                    scheduleClearPresence()
                    promptWhitelist(packageName)
                }
                else -> scheduleClearPresence()
            }
        }
    }

    private fun onAppWhitelisted(packageName: String) {
        promptedPackages.remove(packageName)
        getSystemService(NotificationManager::class.java).cancel(packageName.hashCode())
        val current = detector.foregroundPackage
        if (current == packageName) {
            serviceScope.launch {
                val config = AppDatabase.getDatabase(this@RPCForegroundService)
                    .gameConfigDao()
                    .getConfig(packageName)
                if (config?.isEnabled == true) {
                    activeGamePackage = packageName
                    sendGamePresence(config)
                }
            }
        }
    }

    private fun isIgnoredPackage(packageName: String): Boolean {
        if (packageName == this.packageName) return true
        if (packageName == "android" || packageName == "com.android.systemui") return true
        if (packageName == launcherPackage) return true
        return try {
            val ai = packageManager.getApplicationInfo(packageName, 0)
            (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        } catch (e: Exception) {
            false
        }
    }

    private fun scheduleClearPresence() {
        clearJob?.cancel()
        clearJob = serviceScope.launch {
            delay(30000)
            if (isActive) {
                Logger.i("Game left for 30s. Clearing presence.")
                activeGamePackage = null
                sendClearPresence()
            }
        }
    }

    private suspend fun sendGamePresence(config: GameConfig) {
        // Games in Discord's detectable list are sent under their official application,
        // the same way desktop Discord reports a detected game. Everything else falls
        // back to the user's own application id.
        val official = DetectableCatalog.get(this).lookup(config.packageName)
        val appId = official?.applicationId ?: tokenManager.getApplicationId().orEmpty()
        if (official != null) {
            Logger.i("${config.packageName} matches Discord app \"${official.name}\" (${official.applicationId})")
        }
        val largeImage = com.github.vermilion10.disqrpc.util.AssetResolver.resolve(
            this, assetRegistrar, config.customLargeImage ?: official?.iconUrl
        )
        val smallImage = com.github.vermilion10.disqrpc.util.AssetResolver.resolve(
            this, assetRegistrar, config.customSmallImage
        )
        val payload = PresencePayloadBuilder.buildActivityPayload(
            name = config.gameName,
            appId = appId,
            // An official app already shows "Playing <name>"; don't repeat it as details.
            details = config.customDetails ?: if (official == null) "Playing ${config.gameName}" else null,
            state = config.customState,
            largeImage = largeImage,
            smallImage = smallImage,
            startTime = System.currentTimeMillis(),
            status = config.status
        )
        Logger.d("Game presence payload: $payload")
        updatePresence(payload)
    }

    private fun sendClearPresence() {
        // Preserve the account status (online/idle/dnd/...) chosen by the user instead
        // of always snapping back to "online" when a game's presence is cleared.
        val payload = """{"status":"$lastPresenceStatus","since":0,"activities":[],"afk":false}"""
        updatePresence(payload)
    }

    private fun promptWhitelist(packageName: String) {
        if (!promptedPackages.add(packageName)) return
        val appName = runCatching {
            val ai = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(ai).toString()
        }.getOrElse { packageName }
        Logger.i("Detected new app: $appName ($packageName). Prompting to whitelist.")

        val addIntent = Intent(this, WhitelistReceiver::class.java).apply {
            action = WhitelistReceiver.ACTION_ADD_GAME
            putExtra(WhitelistReceiver.EXTRA_PACKAGE, packageName)
        }
        val pending = PendingIntent.getBroadcast(
            this,
            packageName.hashCode(),
            addIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, PROMPT_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Add $appName to Rich Presence?")
            .setContentText("Whitelist $packageName to show its RPC when you play it.")
            .addAction(0, "Add", pending)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(packageName.hashCode(), notification)
    }

    private fun resolveHomeLauncher(): String? {
        return runCatching {
            val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
            packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
        }.getOrNull()
    }

    private fun createPromptChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                PROMPT_CHANNEL_ID,
                "Game Detection",
                NotificationManager.IMPORTANCE_HIGH
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        gateway.disconnect()
        runCatching {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        }
        stopDetector()
        clearJob?.cancel()
        serviceScope.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(CHANNEL_ID, "Presence Gateway Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(status: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("disqRPC Gateway").setContentText(status).setSmallIcon(R.mipmap.ic_launcher).build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "RPCServiceChannel"
        const val PROMPT_CHANNEL_ID = "GameDetectionChannel"
        const val NOTIFICATION_ID = 1
        const val ACTION_CONNECT = "com.github.vermilion10.disqrpc.CONNECT"
        const val ACTION_DISCONNECT = "com.github.vermilion10.disqrpc.DISCONNECT"
        const val ACTION_UPDATE_PRESENCE = "com.github.vermilion10.disqrpc.UPDATE_PRESENCE"
        const val ACTION_APP_WHITELISTED = "com.github.vermilion10.disqrpc.APP_WHITELISTED"
        const val EXTRA_PAYLOAD = "extra_payload"
        const val EXTRA_PACKAGE = "extra_package"
    }
}
