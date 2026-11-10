package com.github.vermilion10.disqrpc.util

import android.content.Context
import android.net.Uri

/**
 * Turns a stored image value into a key the Discord gateway will render:
 *  - `mp:` keys pass through unchanged
 *  - public URLs are registered with Discord's external-assets endpoint (`mp:external/...`)
 *  - local `content:`/`file:` images are uploaded to catbox.moe first, but only when the
 *    user allowed uploads in Settings; otherwise they are dropped
 */
object AssetResolver {

    fun isLocal(raw: String?) = raw != null && (raw.startsWith("content:") || raw.startsWith("file:"))

    fun resolve(context: Context, registrar: DiscordAssetRegistrar, raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        if (raw.startsWith("mp:")) return raw

        val publicUrl = if (isLocal(raw)) {
            if (!AppSettings(context).allowImageUploads) {
                Logger.w("Skipping a local image: image uploads are off in Settings")
                return null
            }
            ImageUploader.upload(Uri.parse(raw), context) ?: return null
        } else {
            raw
        }

        return registrar.resolve(publicUrl) ?: publicUrl
    }
}
