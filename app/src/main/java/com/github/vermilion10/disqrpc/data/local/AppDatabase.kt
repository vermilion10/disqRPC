package com.github.vermilion10.disqrpc.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.github.vermilion10.disqrpc.data.catalog.DetectableApp
import com.github.vermilion10.disqrpc.data.catalog.DetectableAppDao

@Database(entities = [GameConfig::class, DetectableApp::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun gameConfigDao(): GameConfigDao
    abstract fun detectableAppDao(): DetectableAppDao

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

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "disqrpc_database"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { INSTANCE = it }
            }
        }
    }
}
