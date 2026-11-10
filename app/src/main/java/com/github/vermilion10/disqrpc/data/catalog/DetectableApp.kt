package com.github.vermilion10.disqrpc.data.catalog

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction

/**
 * One Android package from Discord's detectable-applications list. A game with several
 * Play Store listings (lite builds, regional editions) gets one row per package.
 */
@Entity(tableName = "detectable_apps")
data class DetectableApp(
    @PrimaryKey val packageName: String,
    val applicationId: String,
    val name: String,
    val iconHash: String?
) {
    val iconUrl: String?
        get() = iconHash?.let { "https://cdn.discordapp.com/app-icons/$applicationId/$it.png?size=512" }
}

@Dao
abstract class DetectableAppDao {
    @Query("SELECT * FROM detectable_apps WHERE packageName = :packageName")
    abstract suspend fun get(packageName: String): DetectableApp?

    @Query("SELECT COUNT(*) FROM detectable_apps")
    abstract suspend fun count(): Int

    @Query("DELETE FROM detectable_apps")
    protected abstract suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertAll(apps: List<DetectableApp>)

    @Transaction
    open suspend fun replaceAll(apps: List<DetectableApp>) {
        clear()
        insertAll(apps)
    }
}
