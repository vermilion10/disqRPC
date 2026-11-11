package com.github.vermilion10.disqrpc.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import com.github.vermilion10.disqrpc.data.local.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Finds a public icon for a game Discord doesn't list, so its presence isn't shown with a
 * blank image. Tries the game's Google Play artwork first; if the game isn't on Google
 * Play and image uploads are allowed in Settings, uploads the installed app's icon.
 * A found URL is saved on the game, so each game is looked up once.
 */
class AppIconFinder(private val context: Context) {

    private val dao = AppDatabase.getDatabase(context).gameConfigDao()

    suspend fun iconFor(packageName: String): String? {
        val config = dao.getConfig(packageName) ?: return null
        config.autoIconUrl?.let { return it }
        if (packageName in failed) return null

        val url = withContext(Dispatchers.IO) { playStoreIcon(packageName) ?: uploadedIcon(packageName) }
        if (url == null) {
            failed += packageName
            return null
        }
        dao.getConfig(packageName)?.let { dao.updateConfig(it.copy(autoIconUrl = url)) }
        Logger.d("Found an icon for $packageName")
        return url
    }

    private fun playStoreIcon(packageName: String): String? {
        val request = Request.Builder()
            .url("https://play.google.com/store/apps/details?id=$packageName&hl=en")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko)")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val html = response.body?.string() ?: return@use null
                OG_IMAGE.find(html)?.groupValues?.get(1)?.let(::sized)
            }
        } catch (e: Exception) {
            Logger.w("Play Store icon lookup failed: ${e.message ?: e.javaClass.simpleName}")
            null
        }
    }

    private fun uploadedIcon(packageName: String): String? {
        if (!AppSettings(context).allowImageUploads) return null
        val png = runCatching { renderIcon(packageName) }.getOrNull() ?: return null
        return ImageUploader.upload(png, "image/png", "png")
    }

    private fun renderIcon(packageName: String): ByteArray {
        val drawable = context.packageManager.getApplicationIcon(packageName)
        val bitmap = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, ICON_SIZE, ICON_SIZE)
        drawable.draw(Canvas(bitmap))
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    companion object {
        private const val ICON_SIZE = 512
        private val OG_IMAGE = Regex("<meta property=\"og:image\" content=\"([^\"]+)\"")

        /** Packages with no icon found this run; not retried until the app restarts. */
        private val failed: MutableSet<String> = Collections.synchronizedSet(HashSet())

        private val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        /** Play artwork URLs end in `=<size options>`; ask for a 512 px square. */
        internal fun sized(url: String): String {
            if (!url.contains("googleusercontent.com")) return url
            val base = url.substringBeforeLast('=', url)
            return "$base=s512"
        }
    }
}
