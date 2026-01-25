package com.michaeltchuang.walletsdk.core.foundation.database

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

internal fun getAlgoKitDatabase(): AlgoKitDatabase = createAlgoKitDatabase().build()

internal fun createAlgoKitDatabase(customDirectory: String? = null): RoomDatabase.Builder<AlgoKitDatabase> {
    // If using App Group, migrate old database if it exists
    if (customDirectory != null) {
        migrateOldDatabaseIfNeeded(customDirectory)
    }
    
    val baseDir = customDirectory ?: documentDirectory()
    val dbFilePath = "$baseDir/${AlgoKitDatabase.DATABASE_NAME}.db"
    
    // Debug logging
    println("🗄️ Database location: $dbFilePath")
    if (customDirectory != null) {
        println("✅ Using App Group directory")
    } else {
        println("⚠️ Using default Documents directory (App Group not configured)")
    }
    
    return Room
        .databaseBuilder<AlgoKitDatabase>(
            name = dbFilePath,
        ).setDriver(BundledSQLiteDriver())
        .addMigrations(MIGRATION_1_2)
}

/**
 * Migrate database from old location (Documents) to new location (App Group)
 * This runs once when App Group is first configured
 */
@OptIn(ExperimentalForeignApi::class)
private fun migrateOldDatabaseIfNeeded(newDirectory: String) {
    val fileManager = NSFileManager.defaultManager
    val oldDbPath = "${documentDirectory()}/${AlgoKitDatabase.DATABASE_NAME}.db"
    val newDbPath = "$newDirectory/${AlgoKitDatabase.DATABASE_NAME}.db"
    
    // Check if database exists at new location (already migrated or new install)
    if (fileManager.fileExistsAtPath(newDbPath)) {
        return // Already using App Group location
    }
    
    // Check if database exists at old location (needs migration)
    if (!fileManager.fileExistsAtPath(oldDbPath)) {
        return // Fresh install, no data to migrate
    }
    
    // Migrate: Copy old database to new location
    try {
        val error = null
        val success = fileManager.copyItemAtPath(
            oldDbPath,
            toPath = newDbPath,
            error = null
        )
        
        if (success) {
            println("✅ Database migrated from Documents to App Group")
            
            // Also migrate WAL and SHM files if they exist
            listOf("-wal", "-shm").forEach { suffix ->
                val oldFile = "$oldDbPath$suffix"
                val newFile = "$newDbPath$suffix"
                if (fileManager.fileExistsAtPath(oldFile)) {
                    fileManager.copyItemAtPath(oldFile, toPath = newFile, error = null)
                }
            }
            
            // Optionally delete old files after successful migration
            // Uncomment these lines if you want to clean up old location:
            // fileManager.removeItemAtPath(oldDbPath, error = null)
            // fileManager.removeItemAtPath("$oldDbPath-wal", error = null)
            // fileManager.removeItemAtPath("$oldDbPath-shm", error = null)
        } else {
            println("⚠️ Warning: Failed to migrate database")
        }
    } catch (e: Exception) {
        println("⚠️ Warning: Database migration error: ${e.message}")
        // Non-fatal: app will create new database if migration fails
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun documentDirectory(): String {
    val documentDirectory =
        NSFileManager.defaultManager.URLForDirectory(
            directory = NSDocumentDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = false,
            error = null,
        )
    return requireNotNull(documentDirectory?.path)
}
