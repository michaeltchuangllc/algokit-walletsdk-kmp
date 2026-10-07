package com.michaeltchuang.walletsdk.core.solana.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
internal interface SolanaLinkedSignerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SolanaLinkedSignerEntity)

    /** Inserts only if the owner has no session key yet; never overwrites an existing key. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: SolanaLinkedSignerEntity): Long

    @Query("SELECT * FROM solana_linked_signer WHERE owner_address = :ownerAddress")
    suspend fun get(ownerAddress: String): SolanaLinkedSignerEntity?

    @Query("SELECT * FROM solana_linked_signer WHERE owner_address = :ownerAddress")
    fun observe(ownerAddress: String): Flow<SolanaLinkedSignerEntity?>
}
