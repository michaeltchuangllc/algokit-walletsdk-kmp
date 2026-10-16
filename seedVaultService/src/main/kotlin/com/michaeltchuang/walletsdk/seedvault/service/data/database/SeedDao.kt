package com.michaeltchuang.walletsdk.seedvault.service.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
internal interface SeedDao {
    @Transaction
    @Query("SELECT * FROM seed ORDER BY id")
    fun getAllAsFlow(): Flow<List<SeedWithApps>>

    @Query("SELECT encrypted_entropy FROM seed WHERE id = :seedId")
    suspend fun getEncryptedEntropy(seedId: Long): ByteArray?

    @Insert
    suspend fun insert(seed: SeedEntity): Long

    /** Inserts [seed] and names it after its new id, e.g. "Seed 3". Ids are never reused. */
    @Transaction
    suspend fun insertWithDefaultName(seed: SeedEntity): Long {
        val id = insert(seed)
        rename(id, defaultSeedName(id))
        return id
    }

    @Query("UPDATE seed SET name = :name WHERE id = :seedId")
    suspend fun rename(
        seedId: Long,
        name: String,
    )

    @Query("DELETE FROM seed WHERE id = :seedId")
    suspend fun delete(seedId: Long)

    @Query("DELETE FROM authorized_app WHERE seed_id = :seedId AND package_name = :packageName")
    suspend fun revokeApp(
        seedId: Long,
        packageName: String,
    )
}

internal fun defaultSeedName(id: Long) = "Seed $id"
