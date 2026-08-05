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
                    address TEXT NOT NULL,
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
            connection.execSQL("CREATE INDEX IF NOT EXISTS index_passkey_table_address ON passkey_table(address)")
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

val MIGRATION_3_4 =
    object : Migration(3, 4) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS passkey_table_new (
                    credential_id TEXT PRIMARY KEY NOT NULL,
                    site_id INTEGER NOT NULL,
                    address TEXT NOT NULL,
                    user_id TEXT NOT NULL,
                    user_name TEXT NOT NULL,
                    user_display_name TEXT,
                    last_used_time_ms INTEGER,
                    FOREIGN KEY(site_id) REFERENCES sites(id) ON DELETE CASCADE
                )
                """.trimIndent(),
            )

            connection.execSQL(
                """
                INSERT INTO passkey_table_new (
                    credential_id,
                    site_id,
                    address,
                    user_id,
                    user_name,
                    user_display_name,
                    last_used_time_ms
                )
                SELECT
                    credential_id,
                    site_id,
                    algo_address,
                    user_id,
                    user_name,
                    user_display_name,
                    last_used_time_ms
                FROM passkey_table
                """.trimIndent(),
            )

            connection.execSQL("DROP TABLE passkey_table")
            connection.execSQL("ALTER TABLE passkey_table_new RENAME TO passkey_table")
            connection.execSQL("CREATE INDEX IF NOT EXISTS index_passkey_table_site_id ON passkey_table(site_id)")
            connection.execSQL("CREATE INDEX IF NOT EXISTS index_passkey_table_address ON passkey_table(address)")
        }
    }


val MIGRATION_4_5 =
    object : Migration(4, 5) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("""
                CREATE TABLE IF NOT EXISTS falcon_25 (
                    algo_address TEXT PRIMARY KEY NOT NULL,
                    public_key BLOB NOT NULL,
                    encrypted_private_key BLOB NOT NULL
                )
            """.trimIndent())
        }
    }

// Falcon25 entropy is required to reconstruct its 25-word mnemonic. Existing Falcon25 rows
// cannot supply it, so this intentionally drops only those accounts before recreating the table.
val MIGRATION_5_6 =
    object : Migration(5, 6) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("DROP TABLE IF EXISTS falcon_25")
            connection.execSQL("""
                CREATE TABLE falcon_25 (
                    algo_address TEXT PRIMARY KEY NOT NULL,
                    public_key BLOB NOT NULL,
                    encrypted_private_key BLOB NOT NULL,
                    encrypted_entropy BLOB NOT NULL
                )
            """.trimIndent())
        }
    }

// Falcon SDK v0.0.16 signs transactions with a seed derived from entropy. Existing Falcon25
// accounts are intentionally discarded so all newly created accounts have a persisted seed.
val MIGRATION_6_7 =
    object : Migration(6, 7) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("DROP TABLE IF EXISTS falcon_25")
            connection.execSQL("""
                CREATE TABLE falcon_25 (
                    algo_address TEXT PRIMARY KEY NOT NULL,
                    public_key BLOB NOT NULL,
                    encrypted_private_key BLOB NOT NULL,
                    encrypted_entropy BLOB NOT NULL,
                    encrypted_seed BLOB NOT NULL
                )
            """.trimIndent())
        }
    }
