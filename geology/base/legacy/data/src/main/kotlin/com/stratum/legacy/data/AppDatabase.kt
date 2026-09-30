package com.stratum.legacy.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * CHAPTER 09: ROOM APP DATABASE
 *
 * Provides thread-safe, local relational persistence for the Igbo ARPG Engine.
 */

@Database(
    entities = [
        WeaponEntity::class,
        SpriteSheetEntity::class,
        PnpRulebookEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun weaponDao(): WeaponDao
    abstract fun spriteSheetDao(): SpriteSheetDao
    abstract fun pnpRulebookDao(): PnpRulebookDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "igbo_arpg_engine.db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
