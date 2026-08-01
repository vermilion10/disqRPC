package com.github.vermilion10.disqrpc.util

import android.content.Context
import android.net.Uri

/**
 * Turns a stored image value into a key the Discord gateway will render:
 *  - local `content:`/`file:` URIs are uploaded (catbox.moe) first to get a public URL
 *  - the public URL is then registered with Discord's external-assets endpoint,
 *    yielding a `mp:external/...` key
 *  - already-resolved `mp:` keys pass through unchanged
 */
object AssetResolver {

    fun resolve(context: Context, registrar: DiscordAssetRegistrar, raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        if (raw.startsWith("mp:")) return raw

        val publicUrl = when {
            raw.startsWith("content:") || raw.startsWith("file:") ->
                ImageUploader.upload(Uri.parse(raw), context)
            else -> raw
        } ?: return null

        return registrar.resolve(publicUrl) ?: publicUrl
    }
}
