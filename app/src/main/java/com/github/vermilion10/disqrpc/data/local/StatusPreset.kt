package com.github.vermilion10.disqrpc.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * A saved custom status. The form is stored as JSON so new fields can be added without a
 * schema change; an entry that fails to parse after a downgrade is simply skipped.
 */
@Entity(tableName = "status_presets")
data class StatusPreset(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val formJson: String
)

@Dao
interface StatusPresetDao {
    @Query("SELECT * FROM status_presets ORDER BY name COLLATE NOCASE")
    fun getAll(): Flow<List<StatusPreset>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(preset: StatusPreset): Long

    @Delete
    suspend fun delete(preset: StatusPreset)
}
