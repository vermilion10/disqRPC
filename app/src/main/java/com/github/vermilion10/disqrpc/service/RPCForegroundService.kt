package com.github.vermilion10.disqrpc.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.github.vermilion10.disqrpc.R
import com.github.vermilion10.disqrpc.data.local.AppDatabase
import com.github.vermilion10.disqrpc.data.local.GameConfig
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
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class RPCForegroundService : Service() {

    private lateinit var okHttpClient: OkHttpClient
    private var webSocket: WebSocket? = null
    private lateinit var tokenManager: TokenManager
    
    private var isConnected = false
    private var lastSequence: Int? = null
    private var heartbeatInterval: Long = 30000
    private var heartbeatThread: Thread? = null
    private var connectionTimeoutJob: Thread? = null

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
    private var autoReconnect = false
    @Volatile
    private var reconnectPending = false
    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null
    private val assetRegistrar by lazy { com.github.vermilion10.disqrpc.util.DiscordAssetRegistrar(this) }

    override fun onCreate() {
        super.onCreate()
        tokenManager = TokenManager(this)
        okHttpClient = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        
        createNotificationChannel()
        createPromptChannel()
        launcherPackage = resolveHomeLauncher()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification("Disconnected")
        startForeground(NOTIFICATION_ID, notification)
        
        val action = intent?.action
        when (action) {
            ACTION_CONNECT -> connectToGateway()
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

    private fun connectToGateway() {
        if (isConnected) {
            Logger.d("Already connected. Ignoring.")
            return
        }

        ConnectionManager.updateState(ConnectionManager.State.CONNECTING)
        
        connectionTimeoutJob?.interrupt()
        connectionTimeoutJob = Thread {
            try {
                Thread.sleep(25000)
                if (ConnectionManager.state.value == ConnectionManager.State.CONNECTING) {
                    Logger.e("Handshake timeout: READY event not received.")
                    ConnectionManager.updateState(ConnectionManager.State.FAILED)
                    disconnect()
                }
            } catch (e: InterruptedException) {}
        }
        connectionTimeoutJob?.start()

        val token = tokenManager.getToken() ?: ""
        val isBot = token.startsWith("Bot ", ignoreCase = true)
        
        val requestBuilder = Request.Builder()
            .url("wss://gateway.discord.gg/?v=10&encoding=json")
            
        if (isBot) {
            Logger.d("Connecting as Bot")
            requestBuilder.header("User-Agent", "disqRPC (https://github.com/vermilion10/disqrpc, 1.0.0)")
        } else {
            Logger.d("Connecting as User (Desktop Spoofing)")
            val superProps = "eyJvcyI6IldpbmRvd3MiLCJicm93c2VyIjoiRGlzY29yZCBDbGllbnQiLCJyZWxlYXNlX2NoYW5uZWwiOiJzdGFibGUiLCJjbGllbnRfdmVyc2lvbiI6IjEuMC45MTY3Iiwib3NfdmVyc2lvbiI6IjEwLjAuMjI2MzEiLCJvc19hcmNoIjoieDY0Iiwic3lzdGVtX2xvY2FsZSI6ImVuLVVTIiwiY2xpZW50X2J1aWxkX251bWJlciI6MzUwNDQ0LCJuYXRpdmVfYnVpbGRfbnVtYmVyIjpudWxsLCJjbGllbnRfZXZlbnRfc291cmNlIjpudWxsfQ=="
            requestBuilder.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) discord/1.0.9167 Chrome/120.0.6099.291 Electron/28.2.10 Safari/537.36")
            requestBuilder.header("X-Super-Properties", superProps)
            requestBuilder.header("Origin", "https://discord.com")
        }

        webSocket = okHttpClient.newWebSocket(requestBuilder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                Logger.i("WebSocket Opened. Waiting for Hello...")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = JSONObject(text)
                val op = json.getInt("op")
                val t = json.optString("t", "null")
                
                if (op == 0) Logger.d("RECV: OP 0 ($t)") else Logger.d("RECV: OP $op")

                if (json.has("s") && !json.isNull("s")) lastSequence = json.getInt("s")

                when (op) {
                    10 -> { // Hello
                        heartbeatInterval = json.getJSONObject("d").getLong("heartbeat_interval")
                        startHeartbeat()
                        Thread { Thread.sleep(500); sendIdentify() }.start()
                    }
                    11 -> { /* Heartbeat ACK */ }
                    1 -> sendHeartbeat()
                    9 -> {
                        Logger.e("Invalid Session (OP 9).")
                        lastSequence = null
                        autoReconnect = false
                        ConnectionManager.updateState(ConnectionManager.State.FAILED)
                        disconnect()
                    }
                    7 -> {
                        Logger.w("Reconnect (OP 7). Dropping socket; will reconnect.")
                        autoReconnect = true
                        isConnected = false
                        webSocket?.close(1000, "Server requested reconnect")
                    }
                    0 -> { // Dispatch
                        if (t == "READY") {
                            val user = json.getJSONObject("d").getJSONObject("user")
                            val name = user.optString("global_name", user.getString("username"))
                            ConnectionManager.setUsername(name)
                            ConnectionManager.updateState(ConnectionManager.State.CONNECTED)
                            Logger.i("Connected successfully as $name")
                            reconnectAttempts = 0
                            reconnectPending = false
                            autoReconnect = true
                            startDetector()
                            // Presence survives reconnects: re-apply whatever was last sent.
                            ConnectionManager.currentPresence.value?.let { cached ->
                                Logger.d("Re-sending cached presence after reconnect")
                                updatePresence(cached)
                            }
                        }
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Logger.w("WebSocket Closing: $code - $reason")
                isConnected = false
                if (code == 4004) {
                    Logger.e("Critical: Authentication Failed (4004).")
                    autoReconnect = false
                    ConnectionManager.updateState(ConnectionManager.State.FAILED)
                    return
                }
                if (autoReconnect) scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                Logger.e("Connection Failed: ${t.message}")
                if (autoReconnect) {
                    scheduleReconnect()
                } else {
                    ConnectionManager.updateState(ConnectionManager.State.FAILED)
                }
            }
        })
    }

    private fun sendIdentify() {
        val token = tokenManager.getToken() ?: return
        val isBot = token.startsWith("Bot ", ignoreCase = true)
        
        Logger.i("Sending Identify (Bot=$isBot)...")

        val identify = JSONObject().apply {
            put("op", 2)
            put("d", JSONObject().apply {
                put("token", token)
                if (isBot) {
                    put("intents", 0) 
                    put("properties", JSONObject().apply {
                        put("os", "linux"); put("browser", "disqRPC"); put("device", "disqRPC")
                    })
                } else {
                    put("capabilities", 16383)
                    put("properties", JSONObject().apply {
                        put("os", "Windows"); put("browser", "Discord Client")
                        put("release_channel", "stable"); put("client_version", "1.0.9167")
                        put("os_version", "10.0.22631"); put("os_arch", "x64")
                        put("system_locale", "en-US"); put("client_build_number", 350444)
                        put("native_build_number", JSONObject.NULL); put("client_event_source", JSONObject.NULL)
                    })
                    put("presence", JSONObject().apply {
                        put("status", "online"); put("since", 0); put("activities", JSONArray()); put("afk", false)
                    })
                    put("compress", false)
                    put("client_state", JSONObject().apply {
                        put("guild_versions", JSONObject()); put("highest_last_message_id", "0")
                        put("read_state_version", 0); put("user_guild_settings_version", -1)
                        put("user_settings_version", -1); put("private_channels_version", "0")
                        put("api_code_version", 0)
                    })
                    put("consent_properties", JSONObject().apply {
                        put("personalization_data_consent", JSONObject().apply { put("consented", true) })
                    })
                }
            })
        }
        webSocket?.send(identify.toString())
    }

    private fun sendHeartbeat() {
        val heartbeat = JSONObject().apply { put("op", 1); put("d", lastSequence ?: JSONObject.NULL) }
        webSocket?.send(heartbeat.toString())
    }

    private fun startHeartbeat() {
        heartbeatThread?.interrupt()
        heartbeatThread = Thread {
            try {
                while (isConnected) {
                    Thread.sleep(heartbeatInterval)
                    sendHeartbeat()
                }
            } catch (e: InterruptedException) {}
        }
        heartbeatThread?.start()
    }

    private fun updatePresence(payload: String): Boolean {
        if (!isConnected) {
            Logger.e("Cannot update presence: Not connected")
            return false
        }
        return try {
            val presenceUpdate = JSONObject().apply {
                put("op", 3)
                put("d", JSONObject(payload))
            }
            Logger.i(">>> SENDING PRESENCE UPDATE <<<")
            Logger.d("Payload: ${presenceUpdate.toString()}")
            webSocket?.send(presenceUpdate.toString())
            JSONObject(payload).optString("status", "online").let { lastPresenceStatus = it }
            ConnectionManager.setCurrentPresence(payload)
            true
        } catch (e: Exception) {
            Logger.e("Failed to update presence: ${e.message}")
            false
        }
    }

    private fun scheduleReconnect() {
        if (reconnectPending) return
        reconnectPending = true
        reconnectJob = serviceScope.launch {
            reconnectAttempts++
            if (reconnectAttempts > MAX_RECONNECT_ATTEMPTS) {
                reconnectPending = false
                autoReconnect = false
                ConnectionManager.updateState(ConnectionManager.State.FAILED)
                Logger.e("Reconnect attempts exhausted. Giving up.")
                return@launch
            }
            val delayMs = minOf(30_000L, 2_000L * reconnectAttempts)
            Logger.i("Reconnecting in ${delayMs / 1000}s (attempt $reconnectAttempts)...")
            delay(delayMs)
            reconnectPending = false
            if (isActive) connectToGateway()
        }
    }

    private fun disconnect() {
        autoReconnect = false
        reconnectPending = false
        reconnectJob?.cancel()
        stopDetector()
        clearJob?.cancel()
        webSocket?.close(1000, "User requested")
        heartbeatThread?.interrupt()
        connectionTimeoutJob?.interrupt()
        isConnected = false
        ConnectionManager.updateState(ConnectionManager.State.DISCONNECTED)
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

    private fun sendGamePresence(config: GameConfig) {
        val appId = tokenManager.getApplicationId() ?: ""
        val largeImage = com.github.vermilion10.disqrpc.util.AssetResolver.resolve(
            this, assetRegistrar, config.customLargeImage
        )
        val smallImage = com.github.vermilion10.disqrpc.util.AssetResolver.resolve(
            this, assetRegistrar, config.customSmallImage
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
        private const val MAX_RECONNECT_ATTEMPTS = 10
        const val EXTRA_PACKAGE = "extra_package"
    }
}
