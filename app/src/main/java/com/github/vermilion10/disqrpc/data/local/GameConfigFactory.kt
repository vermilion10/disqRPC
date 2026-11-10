package com.github.vermilion10.disqrpc.data.local

import android.content.Context
import com.github.vermilion10.disqrpc.data.catalog.DetectableCatalog

object GameConfigFactory {

    /**
     * Builds the config for a newly whitelisted package. The name comes from Discord's
     * detectable list when the game is in it, otherwise from the installed app's label.
     * Artwork is left empty: catalog games get their official icon at send time, and
     * anything else keeps whatever the user sets later.
     */
    suspend fun create(context: Context, packageName: String): GameConfig {
        val match = DetectableCatalog.get(context).lookup(packageName)
        val name = match?.name ?: installedLabel(context, packageName) ?: packageName
        return GameConfig(packageName = packageName, gameName = name, isEnabled = true)
    }

    private fun installedLabel(context: Context, packageName: String): String? = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
