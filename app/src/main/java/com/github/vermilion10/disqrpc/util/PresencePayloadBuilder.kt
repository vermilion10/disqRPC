package com.github.vermilion10.disqrpc.util

import org.json.JSONArray
import org.json.JSONObject

object PresencePayloadBuilder {

    fun buildActivityPayload(
        name: String,
        appId: String,
        details: String? = null,
        state: String? = null,
        largeImage: String? = null,
        smallImage: String? = null,
        startTime: Long? = null,
        status: String = "online"
    ): String {
        fun formatImage(img: String?): String {
            if (img.isNullOrBlank()) return ""
            // Local URIs (content:/file:) can never be fetched by Discord's media proxy.
            if (img.startsWith("content:") || img.startsWith("file:")) return ""
            // Values reach here already resolved by AssetResolver: either a registered
            // "mp:external/..." key (renders) or a raw URL fallback (may not render).
            return img
        }

        val activity = JSONObject().apply {
            put("name", name)
            put("type", 0)
            if (appId.isNotBlank()) {
                put("application_id", appId)
                put("flags", 1)
            }
            if (!details.isNullOrBlank()) put("details", details)
            if (!state.isNullOrBlank()) put("state", state)

            val assets = JSONObject()
            val large = formatImage(largeImage)
            val small = formatImage(smallImage)
            if (large.isNotBlank()) assets.put("large_image", large)
            if (small.isNotBlank()) assets.put("small_image", small)
            if (assets.length() > 0 || appId.isNotBlank()) {
                put("assets", assets)
            }

            if (startTime != null) {
                put("timestamps", JSONObject().apply { put("start", startTime) })
            }
        }

        return JSONObject().apply {
            put("status", status)
            put("since", 0)
            put("activities", JSONArray().put(activity))
            put("afk", false)
        }.toString()
    }
}
