package com.github.vermilion10.disqrpc.data.catalog

import android.content.Context
import com.github.vermilion10.disqrpc.data.local.AppDatabase
import com.github.vermilion10.disqrpc.data.local.GameConfig
import com.github.vermilion10.disqrpc.util.Logger
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Local copy of the Google Play part of Discord's detectable-applications list, used to
 * map an installed package to its official Discord application (id, name, icon).
 *
 * The endpoint is public, so no token is sent. The list is refreshed at most once per
 * [REFRESH_INTERVAL_MS] and revalidated with its ETag, so an unchanged list costs a 304.
 */
class DetectableCatalog private constructor(context: Context) {

    private val dao = AppDatabase.getDatabase(context).detectableAppDao()
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val refreshLock = Mutex()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun lookup(packageName: String): DetectableApp? = dao.get(packageName)

    /**
     * The Discord game shown for a saved game: the application the user linked by hand,
     * else the one listing this Play package, else a detectable game with exactly the
     * same name (catches games whose entry has no Play Store SKU).
     */
    suspend fun officialFor(config: GameConfig): OfficialGame? {
        config.linkedAppId?.let { id ->
            return OfficialGame(id, config.linkedAppName ?: config.gameName, config.linkedAppIconUrl, linked = true)
        }
        dao.get(config.packageName)?.let { return OfficialGame(it.applicationId, it.name, it.iconUrl) }
        return dao.findGameByName(config.gameName.trim())?.let { OfficialGame(it.applicationId, it.name, it.iconUrl) }
    }

    /**
     * Name search over the detectable list. A pasted application ID (17 to 20 digits) is
     * looked up directly, which also finds Discord games that aren't in the detectable
     * list, such as many mobile-only titles.
     */
    suspend fun searchGames(query: String): List<DetectableGame> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        applicationIdIn(q)?.let { return listOfNotNull(findById(it)) }
        return dao.searchGames(q)
    }

    /** A Discord application by ID: from the local list, or Discord's public RPC info. */
    suspend fun findById(applicationId: String): DetectableGame? {
        dao.getGame(applicationId)?.let { return it }
        remoteApps[applicationId]?.let { return it }
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("https://discord.com/api/v10/applications/$applicationId/rpc")
                .header("User-Agent", USER_AGENT)
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val json = JSONObject(response.body?.string() ?: return@use null)
                    val name = json.optString("name").takeIf { it.isNotBlank() } ?: return@use null
                    val icon = json.optString("icon").takeIf { it.isNotBlank() && it != "null" }
                    DetectableGame(applicationId, name, icon).also { remoteApps[applicationId] = it }
                }
            } catch (e: Exception) {
                Logger.w("Application lookup failed: ${e.message ?: e.javaClass.simpleName}")
                null
            }
        }
    }

    private suspend fun hasData() = dao.count() > 0 && dao.gameCount() > 0

    suspend fun refreshIfStale() {
        val age = System.currentTimeMillis() - prefs.getLong(KEY_LAST_REFRESH, 0L)
        if (age in 0 until REFRESH_INTERVAL_MS && hasData()) return
        refresh()
    }

    /** Returns true when the local table is usable afterwards (fresh, unchanged or kept). */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        refreshLock.withLock {
            val haveRows = hasData()
            val etag = prefs.getString(KEY_ETAG, null).takeIf { haveRows }
            val request = Request.Builder()
                .url(ENDPOINT)
                .header("User-Agent", "disqRPC (https://github.com/vermilion10/disqrpc)")
                .apply { if (etag != null) header("If-None-Match", etag) }
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    when {
                        response.code == 304 -> {
                            markRefreshed(etag)
                            Logger.d("Detectable catalog unchanged")
                            true
                        }
                        response.isSuccessful -> {
                            val body = response.body ?: return@use haveRows
                            val parsed = body.charStream().use(DetectableParser::parse)
                            if (parsed.games.isEmpty()) {
                                Logger.w("Detectable catalog came back empty; keeping the old copy")
                                return@use haveRows
                            }
                            dao.replaceAll(parsed.apps, parsed.games)
                            markRefreshed(response.header("ETag"))
                            Logger.i(
                                "Detectable catalog updated: ${parsed.games.size} games, " +
                                    "${parsed.apps.size} Android packages"
                            )
                            true
                        }
                        else -> {
                            Logger.w("Detectable catalog refresh failed: HTTP ${response.code}")
                            haveRows
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.w("Detectable catalog refresh failed: ${e.message ?: e.javaClass.simpleName}")
                haveRows
            }
        }
    }

    private fun markRefreshed(etag: String?) {
        prefs.edit()
            .putLong(KEY_LAST_REFRESH, System.currentTimeMillis())
            .apply { if (etag != null) putString(KEY_ETAG, etag) else remove(KEY_ETAG) }
            .apply()
    }

    /** Applications looked up by ID that aren't in the local list; kept for this process. */
    private val remoteApps = ConcurrentHashMap<String, DetectableGame>()

    companion object {
        const val ENDPOINT = "https://discord.com/api/v10/applications/detectable"
        private const val USER_AGENT = "disqRPC (https://github.com/vermilion10/disqrpc)"
        private val APP_ID = Regex("\\d{17,20}")
        private val APP_LINK = Regex("discord(?:app)?\\.com/(?:games|application-directory|developers/applications)/(\\d{17,20})")

        /** An application ID typed directly, or taken from a Discord game page link. */
        internal fun applicationIdIn(query: String): String? =
            if (APP_ID.matches(query)) query else APP_LINK.find(query)?.groupValues?.get(1)
        const val REFRESH_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000

        private const val PREFS = "detectable_catalog"
        private const val KEY_ETAG = "etag"
        private const val KEY_LAST_REFRESH = "last_refresh"

        @Volatile
        private var instance: DetectableCatalog? = null

        fun get(context: Context): DetectableCatalog =
            instance ?: synchronized(this) {
                instance ?: DetectableCatalog(context.applicationContext).also { instance = it }
            }
    }
}
