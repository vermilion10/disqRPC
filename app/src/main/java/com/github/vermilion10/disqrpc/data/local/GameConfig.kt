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
    val customState: String? = null
)
