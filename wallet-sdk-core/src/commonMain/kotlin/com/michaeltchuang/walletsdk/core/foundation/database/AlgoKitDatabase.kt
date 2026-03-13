package com.michaeltchuang.walletsdk.core.foundation.database

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import com.michaeltchuang.walletsdk.core.account.data.database.dao.Algo25Dao
import com.michaeltchuang.walletsdk.core.account.data.database.dao.Algo25NoAuthDao
import com.michaeltchuang.walletsdk.core.account.data.database.dao.CustomAccountInfoDao
import com.michaeltchuang.walletsdk.core.account.data.database.dao.CustomHdSeedInfoDao
import com.michaeltchuang.walletsdk.core.account.data.database.dao.Falcon24Dao
import com.michaeltchuang.walletsdk.core.account.data.database.dao.HdKeyDao
import com.michaeltchuang.walletsdk.core.account.data.database.dao.HdSeedDao
import com.michaeltchuang.walletsdk.core.account.data.database.dao.LedgerBleDao
import com.michaeltchuang.walletsdk.core.account.data.database.dao.NoAuthDao
import com.michaeltchuang.walletsdk.core.account.data.database.dao.PasskeyDao
import com.michaeltchuang.walletsdk.core.account.data.database.dao.PasskeySiteDao
import com.michaeltchuang.walletsdk.core.account.data.database.dao.SeedVaultDao
import com.michaeltchuang.walletsdk.core.account.data.database.model.Algo25Entity
import com.michaeltchuang.walletsdk.core.account.data.database.model.CustomAccountInfoEntity
import com.michaeltchuang.walletsdk.core.account.data.database.model.CustomHdSeedInfoEntity
import com.michaeltchuang.walletsdk.core.account.data.database.model.Falcon24Entity
import com.michaeltchuang.walletsdk.core.account.data.database.model.HdKeyEntity
import com.michaeltchuang.walletsdk.core.account.data.database.model.HdSeedEntity
import com.michaeltchuang.walletsdk.core.account.data.database.model.LedgerBleEntity
import com.michaeltchuang.walletsdk.core.account.data.database.model.NoAuthEntity
import com.michaeltchuang.walletsdk.core.account.data.database.model.PasskeyEntity
import com.michaeltchuang.walletsdk.core.account.data.database.model.SiteEntity
import com.michaeltchuang.walletsdk.core.account.data.database.model.SeedVaultEntity

@Database(
    entities = [
        LedgerBleEntity::class,
        NoAuthEntity::class,
        HdKeyEntity::class,
        HdSeedEntity::class,
        Algo25Entity::class,
        Falcon24Entity::class,
        CustomAccountInfoEntity::class,
        CustomHdSeedInfoEntity::class,
        PasskeyEntity::class,
        SiteEntity::class,
        SeedVaultEntity::class,
    ],
    version = AlgoKitDatabase.DATABASE_VERSION,
)
@ConstructedBy(AppDatabaseConstructor::class)
internal abstract class AlgoKitDatabase : RoomDatabase() {
    abstract fun ledgerBleDao(): LedgerBleDao

    abstract fun noAuthDao(): NoAuthDao

    abstract fun hdKeyDao(): HdKeyDao

    abstract fun hdSeedDao(): HdSeedDao

    abstract fun algo25Dao(): Algo25Dao

    abstract fun falcon24Dao(): Falcon24Dao

    abstract fun algo25NoAuthDao(): Algo25NoAuthDao

    abstract fun customAccountInfoDao(): CustomAccountInfoDao

    abstract fun customHdSeedInfoDao(): CustomHdSeedInfoDao

    abstract fun passkeyDao(): PasskeyDao

    abstract fun passkeySiteDao(): PasskeySiteDao

    abstract fun solanaAccountDao(): SeedVaultDao

    companion object Companion {
        const val DATABASE_VERSION = 4 // Bumped for seed_vault table schema change
        const val DATABASE_NAME = "algokit_database"
    }
}

// The Room compiler generates the `actual` implementations.
@Suppress("KotlinNoActualForExpect")
internal expect object AppDatabaseConstructor : RoomDatabaseConstructor<AlgoKitDatabase> {
    override fun initialize(): AlgoKitDatabase
}
