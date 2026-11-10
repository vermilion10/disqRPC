package com.github.vermilion10.disqrpc.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.github.vermilion10.disqrpc.MainActivity
import com.github.vermilion10.disqrpc.R
import com.github.vermilion10.disqrpc.data.catalog.DetectableCatalog
import com.github.vermilion10.disqrpc.data.local.AppDatabase
import com.github.vermilion10.disqrpc.gateway.GatewayClient
import com.github.vermilion10.disqrpc.gateway.GatewayIdentity
import com.github.vermilion10.disqrpc.gateway.GatewayLog
import com.github.vermilion10.disqrpc.gateway.GatewayState
import com.github.vermilion10.disqrpc.gateway.OkHttpGatewayTransport
import com.github.vermilion10.disqrpc.presence.ConfigGameSource
import com.github.vermilion10.disqrpc.presence.PresenceEngine
import com.github.vermilion10.disqrpc.util.ConnectionManager
import com.github.vermilion10.disqrpc.util.Logger
import com.github.vermilion10.disqrpc.util.TokenManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Collections

/**
 * Foreground service that owns one gateway session and wires it to the presence engine
 * and the foreground-app detector. The protocol lives in [GatewayClient], the presence
 * rules in [PresenceEngine]; this class only handles Android lifecycle and notifications.
 */
class RPCForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var gateway: GatewayClient
    private lateinit var engine: PresenceEngine
    private lateinit var detector: ForegroundAppDetector
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val promptedPackages = Collections.synchronizedSet(HashSet<String>())
    @Volatile private var stopWhenDisconnected = false

    override fun onCreate() {
        super.onCreate()
        val tokens = TokenManager(this)
        gateway = GatewayClient(
            transport = OkHttpGatewayTransport(),
            identityProvider = { GatewayIdentity.fromToken(tokens.getToken()) },
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
        engine = PresenceEngine(
            scope = serviceScope,
            source = ConfigGameSource(this),
            sink = ::sendPresence,
            onUnknownApp = ::promptWhitelist,
            log = Logger::i
        )
        val configs = AppDatabase.getDatabase(this).gameConfigDao()
        detector = ForegroundAppDetector(
            context = this,
            scope = serviceScope,
            onForegroundChanged = engine::onForegroundChanged,
            isProtected = { pkg -> configs.getConfig(pkg)?.isEnabled == true }
        )

        createChannels()
        serviceScope.launch { gateway.state.collect(::onGatewayState) }
        // Any add/edit/toggle/delete of a game re-evaluates the current presence.
        serviceScope.launch { configs.getAllConfigs().drop(1).collect { engine.refresh() } }
        serviceScope.launch { DetectableCatalog.get(this@RPCForegroundService).refreshIfStale() }
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, statusNotification(gateway.state.value))

        when (intent?.action) {
            ACTION_CONNECT -> connect()
            ACTION_DISCONNECT -> disconnect()
            ACTION_UPDATE_PRESENCE -> intent.getStringExtra(EXTRA_PAYLOAD)?.let(::setCustomPresence)
            ACTION_CLEAR_PRESENCE -> engine.setCustomPresence(null)
            ACTION_GAME_HINT -> intent.getStringExtra(EXTRA_PACKAGE)?.let(engine::onForegroundChanged)
            null -> {
                // Restarted by the system after the process was killed (START_STICKY).
                if (prefs.getBoolean(KEY_WANT_CONNECTED, false)) {
                    Logger.i("Service restarted by the system; reconnecting")
                    connect()
                } else {
                    shutDown()
                }
            }
        }
        return START_STICKY
    }

    private fun connect() {
        stopWhenDisconnected = false
        prefs.edit().putBoolean(KEY_WANT_CONNECTED, true).apply()
        gateway.connect()
    }

    private fun disconnect() {
        stopWhenDisconnected = true
        prefs.edit().putBoolean(KEY_WANT_CONNECTED, false).apply()
        detector.stop()
        engine.reset()
        // The service stops once the gateway reports Disconnected, so the close frame
        // is sent before the scope is cancelled.
        gateway.disconnect()
    }

    private fun onGatewayState(state: GatewayState) {
        when (state) {
            is GatewayState.Ready -> {
                ConnectionManager.setUsername(state.username)
                ConnectionManager.updateState(ConnectionManager.State.CONNECTED)
                detector.start()
            }
            is GatewayState.Connecting ->
                ConnectionManager.updateState(ConnectionManager.State.CONNECTING)
            is GatewayState.Failed -> {
                prefs.edit().putBoolean(KEY_WANT_CONNECTED, false).apply()
                ConnectionManager.updateState(ConnectionManager.State.FAILED, state.reason)
                shutDown()
            }
            GatewayState.Disconnected -> {
                ConnectionManager.updateState(ConnectionManager.State.DISCONNECTED)
                if (stopWhenDisconnected) shutDown()
            }
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, statusNotification(state))
    }

    private fun setCustomPresence(payload: String) {
        val json = runCatching { JSONObject(payload) }.getOrElse {
            Logger.e("Invalid presence payload: ${it.message}")
            return
        }
        engine.setCustomPresence(json)
    }

    private fun sendPresence(presence: JSONObject) {
        val text = presence.toString()
        Logger.d("Presence payload: $text")
        ConnectionManager.setCurrentPresence(text)
        gateway.updatePresence(presence)
    }

    private fun shutDown() {
        detector.stop()
        engine.reset()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---- Network ----

    /**
     * Tracks physical networks only. The default-network callback is useless here: an
     * always-on VPN (Tailscale, WireGuard...) stays "connected and validated" as the app's
     * default network even when Wi-Fi and mobile data are both gone. NetworkRequest.Builder
     * adds NOT_VPN by default, so this request only matches real networks.
     */
    private val physicalNetworks = Collections.synchronizedSet(HashSet<Network>())

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

    // ---- Notifications ----

    private fun promptWhitelist(packageName: String) {
        if (!promptedPackages.add(packageName)) return
        val appName = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        }.getOrElse { packageName }
        Logger.i("New app in foreground: $appName ($packageName). Asking whether to add it.")

        val add = PendingIntent.getBroadcast(
            this,
            packageName.hashCode(),
            Intent(this, WhitelistReceiver::class.java)
                .setAction(WhitelistReceiver.ACTION_ADD_GAME)
                .putExtra(WhitelistReceiver.EXTRA_PACKAGE, packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, PROMPT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Add $appName to Rich Presence?")
            .setContentText("Show it on your Discord profile while you play.")
            .addAction(0, "Add", add)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(promptId(packageName), notification)
    }

    private fun statusNotification(state: GatewayState): Notification {
        val text = when (state) {
            is GatewayState.Ready -> "Connected as ${state.username}"
            is GatewayState.Connecting ->
                if (state.attempt == 0) "Connecting..." else "Reconnecting (attempt ${state.attempt})..."
            is GatewayState.Failed -> "Failed: ${state.reason}"
            GatewayState.Disconnected -> "Disconnected"
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("disqRPC")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Connection status", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(PROMPT_CHANNEL_ID, "Game detection", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        gateway.disconnect()
        runCatching {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        }
        detector.stop()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "RPCServiceChannel"
        const val PROMPT_CHANNEL_ID = "GameDetectionChannel"
        const val NOTIFICATION_ID = 1

        const val ACTION_CONNECT = "com.github.vermilion10.disqrpc.CONNECT"
        const val ACTION_DISCONNECT = "com.github.vermilion10.disqrpc.DISCONNECT"
        /** Sets a custom status that overrides game presence until cleared. */
        const val ACTION_UPDATE_PRESENCE = "com.github.vermilion10.disqrpc.UPDATE_PRESENCE"
        const val ACTION_CLEAR_PRESENCE = "com.github.vermilion10.disqrpc.CLEAR_PRESENCE"
        /** A whitelisted game showed activity (notification) while Usage Access is off. */
        const val ACTION_GAME_HINT = "com.github.vermilion10.disqrpc.GAME_HINT"

        const val EXTRA_PAYLOAD = "extra_payload"
        const val EXTRA_PACKAGE = "extra_package"

        private const val PREFS = "service_state"
        private const val KEY_WANT_CONNECTED = "want_connected"

        fun promptId(packageName: String) = packageName.hashCode()
    }
}
