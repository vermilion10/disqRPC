package com.github.vermilion10.disqrpc.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.github.vermilion10.disqrpc.data.catalog.DetectableApp
import com.github.vermilion10.disqrpc.data.catalog.DetectableAppDao
import com.github.vermilion10.disqrpc.data.catalog.DetectableGame

@Database(
    entities = [GameConfig::class, DetectableApp::class, DetectableGame::class, StatusPreset::class],
    version = 6,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun gameConfigDao(): GameConfigDao
    abstract fun detectableAppDao(): DetectableAppDao
    abstract fun statusPresetDao(): StatusPresetDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE game_configs ADD COLUMN status TEXT NOT NULL DEFAULT 'online'")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `detectable_apps` (" +
                        "`packageName` TEXT NOT NULL, `applicationId` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, `iconHash` TEXT, PRIMARY KEY(`packageName`))"
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `detectable_games` (" +
                        "`applicationId` TEXT NOT NULL, `name` TEXT NOT NULL, `iconHash` TEXT, " +
                        "PRIMARY KEY(`applicationId`))"
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `status_presets` (" +
                        "`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "`name` TEXT NOT NULL, `formJson` TEXT NOT NULL)"
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf("linkedAppId", "linkedAppName", "linkedAppIconUrl", "autoIconUrl").forEach {
                    db.execSQL("ALTER TABLE game_configs ADD COLUMN `$it` TEXT")
                }
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "disqrpc_database"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .build().also { INSTANCE = it }
            }
        }
    }
}
