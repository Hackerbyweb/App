package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        RelayNodeEntity::class,
        HopChainEntity::class,
        ConnectionLogEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class HopShieldDatabase : RoomDatabase() {
    abstract fun hopShieldDao(): HopShieldDao

    companion object {
        @Volatile
        private var INSTANCE: HopShieldDatabase? = null

        fun getInstance(context: Context): HopShieldDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    HopShieldDatabase::class.java,
                    "hopshield.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
