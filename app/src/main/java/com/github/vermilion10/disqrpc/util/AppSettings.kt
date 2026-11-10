package com.github.vermilion10.disqrpc.util

import android.content.Context

/** Plain (non-secret) user preferences. Credentials live in [TokenManager]. */
class AppSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Whether images picked from the device may be uploaded to catbox.moe, a public file
     * host, so Discord can display them. Off by default: nothing leaves the phone unless
     * the user turns this on.
     */
    var allowImageUploads: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_UPLOADS, false)
        set(value) = prefs.edit().putBoolean(KEY_ALLOW_UPLOADS, value).apply()

    private companion object {
        const val PREFS = "app_settings"
        const val KEY_ALLOW_UPLOADS = "allow_image_uploads"
    }
}
