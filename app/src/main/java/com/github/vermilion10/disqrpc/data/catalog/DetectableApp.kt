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
        get() = DetectableGame.iconUrl(applicationId, iconHash)
}

/** Any game from the detectable list, Android or not; used to pick one for a custom status. */
@Entity(tableName = "detectable_games")
data class DetectableGame(
    @PrimaryKey val applicationId: String,
    val name: String,
    val iconHash: String?
) {
    val iconUrl: String?
        get() = iconUrl(applicationId, iconHash)

    companion object {
        fun iconUrl(applicationId: String, iconHash: String?): String? =
            iconHash?.let { "https://cdn.discordapp.com/app-icons/$applicationId/$it.png?size=512" }
    }
}

/** The Discord application a saved game is shown under. */
data class OfficialGame(
    val applicationId: String,
    val name: String,
    val iconUrl: String?,
    /** True when the user picked it by hand rather than it being matched automatically. */
    val linked: Boolean = false
)

@Dao
abstract class DetectableAppDao {
    @Query("SELECT * FROM detectable_apps WHERE packageName = :packageName")
    abstract suspend fun get(packageName: String): DetectableApp?

    @Query("SELECT * FROM detectable_games WHERE applicationId = :applicationId")
    abstract suspend fun getGame(applicationId: String): DetectableGame?

    @Query("SELECT * FROM detectable_games WHERE name = :name COLLATE NOCASE LIMIT 1")
    abstract suspend fun findGameByName(name: String): DetectableGame?

    @Query("SELECT COUNT(*) FROM detectable_apps")
    abstract suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM detectable_games")
    abstract suspend fun gameCount(): Int

    /** Name search; names starting with the query come first, then shorter names. */
    @Query(
        "SELECT * FROM detectable_games WHERE name LIKE '%' || :query || '%' " +
            "ORDER BY (name LIKE :query || '%') DESC, length(name), name LIMIT :limit"
    )
    abstract suspend fun searchGames(query: String, limit: Int = 50): List<DetectableGame>

    @Query("DELETE FROM detectable_apps")
    protected abstract suspend fun clearApps()

    @Query("DELETE FROM detectable_games")
    protected abstract suspend fun clearGames()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertApps(apps: List<DetectableApp>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertGames(games: List<DetectableGame>)

    @Transaction
    open suspend fun replaceAll(apps: List<DetectableApp>, games: List<DetectableGame>) {
        clearApps()
        clearGames()
        insertApps(apps)
        insertGames(games)
    }
}
