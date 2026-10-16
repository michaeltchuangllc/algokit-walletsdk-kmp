package com.michaeltchuang.walletsdk.seedvault.service.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [SeedEntity::class, AuthorizedAppEntity::class],
    version = 1,
)
internal abstract class SeedVaultDatabase : RoomDatabase() {
    abstract fun seedDao(): SeedDao

    companion object {
        private const val DATABASE_NAME = "seed_vault.db"

        fun build(context: Context): SeedVaultDatabase = Room.databaseBuilder(context, SeedVaultDatabase::class.java, DATABASE_NAME).build()
    }
}
