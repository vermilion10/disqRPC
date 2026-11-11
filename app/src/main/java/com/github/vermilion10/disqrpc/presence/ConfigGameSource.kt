package com.github.vermilion10.disqrpc.presence

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import com.github.vermilion10.disqrpc.data.catalog.DetectableCatalog
import com.github.vermilion10.disqrpc.data.local.AppDatabase
import com.github.vermilion10.disqrpc.util.AppIconFinder
import com.github.vermilion10.disqrpc.util.AssetResolver
import com.github.vermilion10.disqrpc.util.DiscordAssetRegistrar
import com.github.vermilion10.disqrpc.util.Logger
import com.github.vermilion10.disqrpc.util.ActivitySpec
import com.github.vermilion10.disqrpc.util.PresencePayloadBuilder
import com.github.vermilion10.disqrpc.util.TokenManager
import org.json.JSONObject

/** [GameSource] backed by the saved game configs and Discord's detectable catalog. */
class ConfigGameSource(private val context: Context) : GameSource {

    private val dao = AppDatabase.getDatabase(context).gameConfigDao()
    private val catalog = DetectableCatalog.get(context)
    private val tokens = TokenManager(context)
    private val registrar by lazy { DiscordAssetRegistrar(context) }
    private val iconFinder by lazy { AppIconFinder(context) }
    private val launcher: String? by lazy {
        runCatching {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            context.packageManager.resolveActivity(home, 0)?.activityInfo?.packageName
        }.getOrNull()
    }

    override suspend fun classify(packageName: String): GameSource.Kind {
        val config = dao.getConfig(packageName)
        return when {
            config?.isEnabled == true -> GameSource.Kind.GAME
            config != null -> GameSource.Kind.DISABLED
            isIgnored(packageName) -> GameSource.Kind.IGNORED
            else -> GameSource.Kind.UNKNOWN
        }
    }

    override suspend fun presenceFor(packageName: String, startedAt: Long): JSONObject? {
        val config = dao.getConfig(packageName)?.takeIf { it.isEnabled } ?: return null
        // Games in Discord's detectable list are sent under their official application,
        // the same way desktop Discord reports a detected game. Everything else falls
        // back to the user's own application id.
        val official = catalog.officialFor(config)
        if (official != null) {
            Logger.d("$packageName is Discord app \"${official.name}\" (${official.applicationId})")
        }
        // Unlisted games, and Discord games without an icon, get their store or app icon
        // instead of a blank image.
        val image = config.customLargeImage ?: official?.iconUrl ?: iconFinder.iconFor(packageName)
        return PresencePayloadBuilder.build(
            ActivitySpec(
                name = official?.name ?: config.gameName,
                appId = official?.applicationId ?: tokens.getApplicationId().orEmpty(),
                details = config.customDetails,
                state = config.customState,
                largeImage = AssetResolver.resolve(context, registrar, image),
                smallImage = AssetResolver.resolve(context, registrar, config.customSmallImage),
                startTime = startedAt,
                accountStatus = config.status
            )
        )
    }

    private fun isIgnored(packageName: String): Boolean {
        if (packageName == context.packageName) return true
        if (packageName == "android" || packageName == "com.android.systemui") return true
        if (packageName == launcher) return true
        return runCatching {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        }.getOrDefault(false)
    }
}
