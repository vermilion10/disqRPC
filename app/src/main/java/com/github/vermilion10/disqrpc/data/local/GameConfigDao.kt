package com.github.vermilion10.disqrpc.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface GameConfigDao {
    @Query("SELECT * FROM game_configs")
    fun getAllConfigs(): Flow<List<GameConfig>>

    @Query("SELECT * FROM game_configs WHERE packageName = :packageName")
    suspend fun getConfig(packageName: String): GameConfig?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConfig(config: GameConfig)

    @Update
    suspend fun updateConfig(config: GameConfig)

    @Delete
    suspend fun deleteConfig(config: GameConfig)
}
