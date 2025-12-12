package com.michaeltchuang.walletsdk.core.foundation.database

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SQLiteConnection) {
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
