package com.michaeltchuang.walletsdk.core.foundation.database

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers

internal fun getAlgoKitDatabase(context: Context): AlgoKitDatabase =
    createAlgoKitDatabase(context).build()

internal fun createAlgoKitDatabase(context: Context): RoomDatabase.Builder<AlgoKitDatabase> {
    val appContext = context.applicationContext
    val dbFile = appContext.getDatabasePath(AlgoKitDatabase.DATABASE_NAME)
    return Room
        .databaseBuilder<AlgoKitDatabase>(
            context = appContext,
            name = dbFile.absolutePath,
        )
        .setQueryCoroutineContext(Dispatchers.IO)
        .addMigrations(MIGRATION_1_2)
}

// Migration to add passkey tables
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Create sites table
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sites (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                url TEXT NOT NULL,
                name TEXT NOT NULL
            )
        """.trimIndent()
        )

        // Create passkey_table
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS passkey_table (
                credential_id TEXT PRIMARY KEY NOT NULL,
                site_id INTEGER NOT NULL,
                algo_address TEXT NOT NULL,
                user_id TEXT NOT NULL,
                user_name TEXT NOT NULL,
                user_display_name TEXT,
                last_used_time_ms INTEGER,
                FOREIGN KEY(site_id) REFERENCES sites(id) ON DELETE CASCADE
            )
        """.trimIndent()
        )

        // Create index for faster lookups
        db.execSQL("CREATE INDEX IF NOT EXISTS index_passkey_table_site_id ON passkey_table(site_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_passkey_table_algo_address ON passkey_table(algo_address)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_sites_url ON sites(url)")
    }
}
