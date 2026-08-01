package com.github.vermilion10.disqrpc.util

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class TokenManager(private val context: Context) {
    private val masterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val sharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            context,
            "secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun saveToken(token: String) {
        sharedPreferences.edit().putString("discord_token", token).apply()
    }

    fun getToken(): String? {
        return sharedPreferences.getString("discord_token", null)
    }

    fun clearToken() {
        sharedPreferences.edit().remove("discord_token").apply()
    }
    
    fun saveApplicationId(appId: String) {
        sharedPreferences.edit().putString("application_id", appId).apply()
    }
    
    fun getApplicationId(): String? {
        return sharedPreferences.getString("application_id", null)
    }
}
