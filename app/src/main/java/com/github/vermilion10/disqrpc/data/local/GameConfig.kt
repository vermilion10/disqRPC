package com.github.vermilion10.disqrpc.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "game_configs")
data class GameConfig(
    @PrimaryKey val packageName: String,
    val gameName: String,
    val isEnabled: Boolean = true,
    val customLargeImage: String? = null,
    val customSmallImage: String? = null,
    val customDetails: String? = null,
    val customState: String? = null,
    val status: String = "online",
    /**
     * Discord application the user linked this game to by hand, for games whose Play
     * package isn't in the detectable list. Name and icon are kept so presence needs no
     * lookup.
     */
    val linkedAppId: String? = null,
    val linkedAppName: String? = null,
    val linkedAppIconUrl: String? = null,
    /** Public icon found for a game Discord doesn't know (Play Store artwork or an upload). */
    val autoIconUrl: String? = null
)
