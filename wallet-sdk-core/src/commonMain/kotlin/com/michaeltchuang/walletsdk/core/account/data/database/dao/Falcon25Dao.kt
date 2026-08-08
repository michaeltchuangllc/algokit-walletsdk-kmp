package com.michaeltchuang.walletsdk.core.account.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.michaeltchuang.walletsdk.core.account.data.database.model.Falcon25Entity
import kotlinx.coroutines.flow.Flow

@Dao
internal interface Falcon25Dao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: Falcon25Entity)

    @Query("SELECT * FROM falcon_25")
    suspend fun getAll(): List<Falcon25Entity>

    @Query("SELECT algo_address FROM falcon_25")
    suspend fun getAllAddresses(): List<String>

    @Query("SELECT * FROM falcon_25")
    fun getAllAsFlow(): Flow<List<Falcon25Entity>>

    @Query("SELECT COUNT(*) FROM falcon_25")
    fun getTableSizeAsFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM falcon_25")
    suspend fun getTableSize(): Int

    @Query("SELECT * FROM falcon_25 WHERE algo_address = :address")
    suspend fun get(address: String): Falcon25Entity?

    @Query("DELETE FROM falcon_25 WHERE algo_address = :address")
    suspend fun delete(address: String)

    @Query("DELETE FROM falcon_25")
    suspend fun clearAll()
}
