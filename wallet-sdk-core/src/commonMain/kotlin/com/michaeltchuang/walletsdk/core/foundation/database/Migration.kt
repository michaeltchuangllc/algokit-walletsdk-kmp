package com.michaeltchuang.walletsdk.core.foundation.database

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

val MIGRATION_1_2 =
    object : Migration(1, 2) {
        override fun migrate(connection: SQLiteConnection) {
            // Create sites table
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS sites (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    url TEXT NOT NULL,
                    name TEXT NOT NULL
                )
                """.trimIndent(),
            )

            // Create passkey_table
            connection.execSQL(
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
                """.trimIndent(),
            )

            // Create index for faster lookups
            connection.execSQL("CREATE INDEX IF NOT EXISTS index_passkey_table_site_id ON passkey_table(site_id)")
            connection.execSQL("CREATE INDEX IF NOT EXISTS index_passkey_table_algo_address ON passkey_table(algo_address)")
            connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_sites_url ON sites(url)")
        }
    }

val MIGRATION_2_3 =
    object : Migration(2, 3) {
        override fun migrate(connection: SQLiteConnection) {
            // Drop old solana_account table if it exists
            connection.execSQL("DROP TABLE IF EXISTS solana_account")

            // Create new seed_vault table with account_name column
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS seed_vault (
                    public_key TEXT PRIMARY KEY NOT NULL,
                    address TEXT NOT NULL,
                    chainId TEXT NOT NULL,
                    account_name TEXT
                )
                """.trimIndent(),
            )
        }
    }
