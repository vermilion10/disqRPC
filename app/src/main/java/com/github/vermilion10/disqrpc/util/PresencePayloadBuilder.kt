package com.github.vermilion10.disqrpc.util

import org.json.JSONArray
import org.json.JSONObject

/** One label-and-link button shown under a presence. */
data class PresenceButton(val label: String, val url: String)

/** Everything one Rich Presence activity can carry. Blank strings count as absent. */
data class ActivitySpec(
    val name: String,
    val appId: String = "",
    /** 0 Playing, 2 Listening, 3 Watching, 5 Competing. */
    val type: Int = TYPE_PLAYING,
    /** Which line the member list shows: 0 name, 1 state, 2 details. */
    val statusDisplay: Int = DISPLAY_NAME,
    val details: String? = null,
    val detailsUrl: String? = null,
    val state: String? = null,
    val stateUrl: String? = null,
    val largeImage: String? = null,
    val largeText: String? = null,
    val largeUrl: String? = null,
    val smallImage: String? = null,
    val smallText: String? = null,
    val startTime: Long? = null,
    val endTime: Long? = null,
    val partySize: Int? = null,
    val partyMax: Int? = null,
    val buttons: List<PresenceButton> = emptyList(),
    val accountStatus: String = "online"
) {
    companion object {
        const val TYPE_PLAYING = 0
        const val TYPE_LISTENING = 2
        const val TYPE_WATCHING = 3
        const val TYPE_COMPETING = 5

        const val DISPLAY_NAME = 0
        const val DISPLAY_STATE = 1
        const val DISPLAY_DETAILS = 2
    }
}

object PresencePayloadBuilder {

    /** Full `d` object for a PRESENCE_UPDATE (op 3) built from [spec]. */
    fun build(spec: ActivitySpec): JSONObject {
        val activity = JSONObject().apply {
            put("name", spec.name.ifBlank { "Custom status" })
            put("type", spec.type)
            put("status_display_type", spec.statusDisplay)
            if (spec.appId.isNotBlank()) {
                put("application_id", spec.appId)
                put("flags", 1)
            }
            putText("details", spec.details)
            putText("details_url", urlOrNull(spec.detailsUrl))
            putText("state", spec.state)
            putText("state_url", urlOrNull(spec.stateUrl))

            val assets = JSONObject()
            renderable(spec.largeImage)?.let { assets.put("large_image", it) }
            spec.largeText?.takeIf { it.isNotBlank() }?.let { assets.put("large_text", it) }
            urlOrNull(spec.largeUrl)?.let { assets.put("large_url", it) }
            renderable(spec.smallImage)?.let { assets.put("small_image", it) }
            spec.smallText?.takeIf { it.isNotBlank() }?.let { assets.put("small_text", it) }
            if (assets.length() > 0) put("assets", assets)

            timestamps(spec.startTime, spec.endTime)?.let { put("timestamps", it) }
            party(spec.partySize, spec.partyMax)?.let { put("party", it) }

            val valid = spec.buttons.filter { it.label.isNotBlank() && urlOrNull(it.url) != null }
            if (valid.isNotEmpty()) {
                put("buttons", JSONArray(valid.map { it.label }))
                put("metadata", JSONObject().put("button_urls", JSONArray(valid.map { it.url.trim() })))
            }
        }
        return JSONObject().apply {
            put("status", spec.accountStatus)
            put("since", 0)
            put("activities", JSONArray().put(activity))
            put("afk", false)
        }
    }

    /** Status with no activity, keeping the chosen account status. */
    fun clear(accountStatus: String = "online"): JSONObject = JSONObject().apply {
        put("status", accountStatus)
        put("since", 0)
        put("activities", JSONArray())
        put("afk", false)
    }

    private fun JSONObject.putText(key: String, value: String?) {
        value?.takeIf { it.isNotBlank() }?.let { put(key, it) }
    }

    private fun urlOrNull(url: String?): String? =
        url?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

    /**
     * Local URIs can't be fetched by Discord, so they are dropped here. Everything else is
     * already resolved by AssetResolver: a registered `mp:external/...` key or a raw URL.
     */
    private fun renderable(image: String?): String? {
        val value = image?.takeIf { it.isNotBlank() } ?: return null
        if (value.startsWith("content:") || value.startsWith("file:")) return null
        return value
    }

    private fun timestamps(start: Long?, end: Long?): JSONObject? {
        if (start == null && end == null) return null
        return JSONObject().apply {
            start?.let { put("start", it) }
            end?.let { put("end", it) }
        }
    }

    private fun party(size: Int?, max: Int?): JSONObject? {
        if (size == null && max == null) return null
        val s = size ?: 0
        val m = maxOf(max ?: s, s, 1)
        return JSONObject().put("size", JSONArray().put(s.coerceAtLeast(0)).put(m))
    }
}
