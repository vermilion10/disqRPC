package com.github.vermilion10.disqrpc.util

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Registers external image URLs with Discord's API so the gateway will accept them
 * as Rich Presence assets.
 *
 * Discord only renders `mp:external/...` keys that were actually registered via
 * `POST /api/v9/applications/{application_id}/external-assets`. Fabricated keys are
 * silently ignored. The returned `external_asset_path` is cached per URL (persisted)
 * so each unique image is only registered once.
 */
class DiscordAssetRegistrar(private val context: Context) {

    private val prefs = context.getSharedPreferences("asset_registry", Context.MODE_PRIVATE)
    private val memoryCache = ConcurrentHashMap<String, String>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val desktopUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) discord/1.0.9167 Chrome/120.0.6099.291 " +
        "Electron/28.2.10 Safari/537.36"

    /**
     * Resolves a public image URL to an asset key usable in `large_image`/`small_image`
     * (e.g. `mp:external/abc123`). Already-registered keys are returned as-is.
     * Returns null when registration is not possible (no token/appId/network).
     */
    fun resolve(url: String): String? {
        if (url.isBlank()) return null
        if (url.startsWith("mp:")) return url

        memoryCache[url]?.let { return it }
        prefs.getString(url, null)?.let {
            memoryCache[url] = it
            return it
        }

        val token = TokenManager(context).getToken() ?: return null
        val appId = TokenManager(context).getApplicationId() ?: return null

        return try {
            val payload = JSONObject()
                .put("urls", JSONArray().put(url))
                .toString()
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("https://discord.com/api/v9/applications/$appId/external-assets")
                .header("Authorization", token)
                .header("Origin", "https://discord.com")
                .header("User-Agent", desktopUserAgent)
                .post(payload)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Logger.e("external-assets registration failed: ${response.code} ${response.body?.string()}")
                    null
                } else {
                    val text = response.body?.string() ?: return@use null
                    val array = JSONArray(text)
                    if (array.length() == 0) {
                        Logger.e("external-assets returned no entries for $url")
                        null
                    } else {
                        val path = array.getJSONObject(0).getString("external_asset_path")
                        val key = "mp:$path"
                        memoryCache[url] = key
                        prefs.edit().putString(url, key).apply()
                        key
                    }
                }
            }
        } catch (e: Exception) {
            Logger.e("Asset registration failed: ${e.message}")
            null
        }
    }
}
