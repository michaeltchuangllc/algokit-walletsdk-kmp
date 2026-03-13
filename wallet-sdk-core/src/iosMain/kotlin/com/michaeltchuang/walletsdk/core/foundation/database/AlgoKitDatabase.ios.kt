package com.michaeltchuang.walletsdk.core.foundation.database

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSError
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
        .addMigrations(MIGRATION_1_2, MIGRATION_3_4)
}

/**
 * Migrate database from old location (Documents) to new location (App Group)
 * This runs once when App Group is first configured
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun migrateOldDatabaseIfNeeded(newDirectory: String) {
    val fileManager = NSFileManager.defaultManager
    val oldDbPath = "${documentDirectory()}/${AlgoKitDatabase.DATABASE_NAME}.db"
    val newDbPath = "$newDirectory/${AlgoKitDatabase.DATABASE_NAME}.db"

    println("📦 Migration check:")
    println("  Old path: $oldDbPath")
    println("  New path: $newDbPath")

    // Check if database exists at new location (already migrated or new install)
    if (fileManager.fileExistsAtPath(newDbPath)) {
        println("  ✅ Database already exists at App Group location (no migration needed)")
        return
    }

    // Check if database exists at old location (needs migration)
    if (!fileManager.fileExistsAtPath(oldDbPath)) {
        println("  ℹ️ No old database found (fresh install)")
        return
    }

    println("  🔄 Migrating database from old location to App Group...")

    // Migrate: Copy old database to new location
    try {
        // Copy main database file
        memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()
            val success =
                fileManager.copyItemAtPath(
                    oldDbPath,
                    toPath = newDbPath,
                    error = errorPtr.ptr,
                )

            if (success) {
                println("  ✅ Main database file migrated successfully")

                // Also migrate WAL and SHM files if they exist
                listOf("-wal", "-shm").forEach { suffix ->
                    val oldFile = "$oldDbPath$suffix"
                    val newFile = "$newDbPath$suffix"
                    if (fileManager.fileExistsAtPath(oldFile)) {
                        val walErrorPtr = alloc<ObjCObjectVar<NSError?>>()
                        val walSuccess =
                            fileManager.copyItemAtPath(
                                oldFile,
                                toPath = newFile,
                                error = walErrorPtr.ptr,
                            )
                        if (walSuccess) {
                            println("  ✅ Migrated $suffix file")
                        } else {
                            println("  ⚠️ Failed to migrate $suffix file: ${walErrorPtr.value?.localizedDescription}")
                        }
                    }
                }

                println("✅ Database migration complete!")

                // Optionally delete old files after successful migration
                // Uncomment these lines if you want to clean up old location:
                // fileManager.removeItemAtPath(oldDbPath, error = null)
                // fileManager.removeItemAtPath("$oldDbPath-wal", error = null)
                // fileManager.removeItemAtPath("$oldDbPath-shm", error = null)
            } else {
                val error = errorPtr.value
                println("  ❌ Failed to migrate database: ${error?.localizedDescription ?: "Unknown error"}")
            }
        }
    } catch (e: Exception) {
        println("  ❌ Database migration exception: ${e.message}")
        e.printStackTrace()
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
