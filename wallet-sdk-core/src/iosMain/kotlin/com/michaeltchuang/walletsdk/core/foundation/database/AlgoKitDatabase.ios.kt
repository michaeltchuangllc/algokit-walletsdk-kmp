package com.michaeltchuang.walletsdk.core.foundation.database

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

internal fun getAlgoKitDatabase(): AlgoKitDatabase = createAlgoKitDatabase().build()

internal fun createAlgoKitDatabase(): RoomDatabase.Builder<AlgoKitDatabase> {
    val dbFilePath = documentDirectory() + "/${AlgoKitDatabase.DATABASE_NAME}.db"
    return Room
        .databaseBuilder<AlgoKitDatabase>(
            name = dbFilePath,
        ).setDriver(BundledSQLiteDriver()).addMigrations(MIGRATION_1_2)
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
