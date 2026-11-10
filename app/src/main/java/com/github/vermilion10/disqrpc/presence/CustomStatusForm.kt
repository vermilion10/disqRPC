package com.github.vermilion10.disqrpc.presence

import com.github.vermilion10.disqrpc.util.ActivitySpec
import com.github.vermilion10.disqrpc.util.PresenceButton

/** When the elapsed/remaining timer starts counting from. */
enum class TimestampMode { NONE, NOW, LOCAL_TIME, CUSTOM }

/**
 * The editable state of the custom-status screen. Kept separate from [ActivitySpec] so the
 * UI can hold raw text (image URIs not yet uploaded, an app id still to resolve) while the
 * service receives a resolved spec. Also the stored shape of a saved preset.
 */
data class CustomStatusForm(
    val name: String = "",
    val type: Int = ActivitySpec.TYPE_PLAYING,
    val statusDisplay: Int = ActivitySpec.DISPLAY_NAME,
    val details: String = "",
    val detailsUrl: String = "",
    val state: String = "",
    val stateUrl: String = "",
    val largeImage: String = "",
    val largeText: String = "",
    val largeUrl: String = "",
    val smallImage: String = "",
    val smallText: String = "",
    val timestampMode: TimestampMode = TimestampMode.NOW,
    /** For CUSTOM: millis since epoch, or null for "none". */
    val customStart: Long? = null,
    val customEnd: Long? = null,
    val partyEnabled: Boolean = false,
    val partySize: Int = 1,
    val partyMax: Int = 1,
    val button1Label: String = "",
    val button1Url: String = "",
    val button2Label: String = "",
    val button2Url: String = "",
    val accountStatus: String = "online",
    /** Set when an official game is chosen; its name and icon override [name]/[largeImage]. */
    val officialAppId: String? = null,
    val officialName: String? = null,
    val officialIconUrl: String? = null
) {
    val buttons: List<PresenceButton>
        get() = listOf(
            PresenceButton(button1Label, button1Url),
            PresenceButton(button2Label, button2Url)
        ).filter { it.label.isNotBlank() && it.url.isNotBlank() }

    /** Resolves the start/end timestamps for the chosen [timestampMode], at [now]. */
    fun resolveTimestamps(now: Long, startOfToday: Long): Pair<Long?, Long?> = when (timestampMode) {
        TimestampMode.NONE -> null to null
        TimestampMode.NOW -> now to null
        TimestampMode.LOCAL_TIME -> startOfToday to null
        TimestampMode.CUSTOM -> customStart to customEnd
    }
}
