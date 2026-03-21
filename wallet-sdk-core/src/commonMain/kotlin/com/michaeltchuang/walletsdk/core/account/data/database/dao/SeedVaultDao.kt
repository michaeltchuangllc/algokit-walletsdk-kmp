package com.michaeltchuang.walletsdk.core.account.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.michaeltchuang.walletsdk.core.account.data.database.model.SeedVaultEntity
import kotlinx.coroutines.flow.Flow

@Dao
internal interface SeedVaultDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SeedVaultEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<SeedVaultEntity>)

    @Query("SELECT * FROM seed_vault")
    suspend fun getAll(): List<SeedVaultEntity>

    @Query("SELECT address FROM seed_vault")
    suspend fun getAllAddresses(): List<String>

    @Query("SELECT * FROM seed_vault")
    fun getAllAsFlow(): Flow<List<SeedVaultEntity>>

    @Query("SELECT COUNT(*) FROM seed_vault")
    fun getTableSizeAsFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM seed_vault")
    suspend fun getTableSize(): Int

    @Query("SELECT * FROM seed_vault WHERE :publicKey = public_key")
    suspend fun get(publicKey: String): SeedVaultEntity?

    @Query("DELETE FROM seed_vault WHERE :publicKey = public_key")
    suspend fun delete(publicKey: String)

    @Query("DELETE FROM seed_vault WHERE :address = address")
    suspend fun deleteByAddress(address: String)

    @Query("DELETE FROM seed_vault")
    suspend fun clearAll()

    @Query("SELECT EXISTS(SELECT * FROM seed_vault WHERE :address = address)")
    suspend fun isAddressExists(address: String): Boolean

    @Query("UPDATE seed_vault SET account_name = :accountName WHERE :address = address")
    suspend fun updateAccountNameByAddress(
        address: String,
        accountName: String?,
    )

    @Query("SELECT * FROM seed_vault WHERE :chainId = chainId")
    suspend fun getByChainId(chainId: String): List<SeedVaultEntity>

    @Query("SELECT * FROM seed_vault WHERE :chainId = chainId")
    fun getByChainIdAsFlow(chainId: String): Flow<List<SeedVaultEntity>>
}
