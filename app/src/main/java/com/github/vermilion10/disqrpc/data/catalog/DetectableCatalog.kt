package com.github.vermilion10.disqrpc.data.catalog

import android.content.Context
import com.github.vermilion10.disqrpc.data.local.AppDatabase
import com.github.vermilion10.disqrpc.util.Logger
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

    suspend fun refreshIfStale() {
        val age = System.currentTimeMillis() - prefs.getLong(KEY_LAST_REFRESH, 0L)
        if (age in 0 until REFRESH_INTERVAL_MS && dao.count() > 0) return
        refresh()
    }

    /** Returns true when the local table is usable afterwards (fresh, unchanged or kept). */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        refreshLock.withLock {
            val haveRows = dao.count() > 0
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
                            val apps = body.charStream().use(DetectableParser::parse)
                            if (apps.isEmpty()) {
                                Logger.w("Detectable catalog came back empty; keeping the old copy")
                                return@use haveRows
                            }
                            dao.replaceAll(apps)
                            markRefreshed(response.header("ETag"))
                            Logger.i("Detectable catalog updated: ${apps.size} Android packages")
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

    companion object {
        const val ENDPOINT = "https://discord.com/api/v10/applications/detectable"
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
