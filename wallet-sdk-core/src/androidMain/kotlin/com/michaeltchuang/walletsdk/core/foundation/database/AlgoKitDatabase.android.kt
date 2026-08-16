package com.michaeltchuang.walletsdk.core.foundation.database

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.Dispatchers

internal fun getAlgoKitDatabase(context: Context): AlgoKitDatabase = createAlgoKitDatabase(context).build()

internal fun createAlgoKitDatabase(context: Context): RoomDatabase.Builder<AlgoKitDatabase> {
    val appContext = context.applicationContext
    val dbFile = appContext.getDatabasePath(AlgoKitDatabase.DATABASE_NAME)
    return Room
        .databaseBuilder<AlgoKitDatabase>(
            context = appContext,
            name = dbFile.absolutePath,
        ).setQueryCoroutineContext(Dispatchers.IO)
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
}
