package com.github.vermilion10.disqrpc.gateway

import org.json.JSONArray
import org.json.JSONObject

/**
 * User-token credentials plus the handshake headers and IDENTIFY payload that go with them.
 *
 * Bot tokens are rejected: a bot's presence is shown on the bot account, never on yours,
 * so they cannot do what this app is for.
 */
class GatewayIdentity private constructor(val token: String) {

    val headers: Map<String, String>
        get() = mapOf(
            "User-Agent" to DESKTOP_USER_AGENT,
            "X-Super-Properties" to DESKTOP_SUPER_PROPERTIES,
            "Origin" to "https://discord.com"
        )

    /** The `d` object of an IDENTIFY (op 2). [presence] is applied as part of the login. */
    fun identifyPayload(presence: JSONObject?): JSONObject = JSONObject().apply {
        put("token", token)
        put("capabilities", 16383)
        put("properties", JSONObject().apply {
            put("os", "Windows"); put("browser", "Discord Client")
            put("release_channel", "stable"); put("client_version", "1.0.9167")
            put("os_version", "10.0.22631"); put("os_arch", "x64")
            put("system_locale", "en-US"); put("client_build_number", 350444)
            put("native_build_number", JSONObject.NULL); put("client_event_source", JSONObject.NULL)
        })
        put("presence", presence ?: emptyPresence())
        put("compress", false)
        put("client_state", JSONObject().apply {
            put("guild_versions", JSONObject()); put("highest_last_message_id", "0")
            put("read_state_version", 0); put("user_guild_settings_version", -1)
            put("user_settings_version", -1); put("private_channels_version", "0")
            put("api_code_version", 0)
        })
    }

    companion object {
        /** Returns a problem with [raw] that should stop a connect, or null when it is usable. */
        fun problemWith(raw: String?): String? {
            val trimmed = raw?.trim().orEmpty()
            return when {
                trimmed.isEmpty() -> "No token saved"
                trimmed.startsWith("Bot ", ignoreCase = true) ->
                    "Bot tokens are not supported: a bot's presence shows on the bot, not on your profile"
                else -> null
            }
        }

        /** Builds the identity, or throws [IllegalArgumentException] with [problemWith]'s reason. */
        fun fromToken(raw: String?): GatewayIdentity {
            problemWith(raw)?.let { throw IllegalArgumentException(it) }
            return GatewayIdentity(raw!!.trim())
        }

        fun emptyPresence(status: String = "online"): JSONObject = JSONObject().apply {
            put("status", status); put("since", 0); put("activities", JSONArray()); put("afk", false)
        }

        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "discord/1.0.9167 Chrome/120.0.6099.291 Electron/28.2.10 Safari/537.36"
        private const val DESKTOP_SUPER_PROPERTIES =
            "eyJvcyI6IldpbmRvd3MiLCJicm93c2VyIjoiRGlzY29yZCBDbGllbnQiLCJyZWxlYXNlX2NoYW5uZWwiOiJzdGFibGUiLCJjbGllbnRfdmVyc2lvbiI6IjEuMC45MTY3Iiwib3NfdmVyc2lvbiI6IjEwLjAuMjI2MzEiLCJvc19hcmNoIjoieDY0Iiwic3lzdGVtX2xvY2FsZSI6ImVuLVVTIiwiY2xpZW50X2J1aWxkX251bWJlciI6MzUwNDQ0LCJuYXRpdmVfYnVpbGRfbnVtYmVyIjpudWxsLCJjbGllbnRfZXZlbnRfc291cmNlIjpudWxsfQ=="
    }
}
