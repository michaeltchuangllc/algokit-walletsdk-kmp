package com.michaeltchuang.walletsdk.seedvault.service.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
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

    @Query("SELECT address FROM seed WHERE id = :seedId")
    suspend fun getAddress(seedId: Long): String?

    @Query("SELECT public_key FROM seed WHERE id = :seedId")
    suspend fun getPublicKey(seedId: Long): ByteArray?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAuthorizedApp(app: AuthorizedAppEntity)

    @Query("SELECT encrypted_private_key FROM seed WHERE id = :seedId")
    suspend fun getEncryptedPrivateKey(seedId: Long): ByteArray?

    @Transaction
    @Query("SELECT * FROM seed WHERE id = :seedId")
    suspend fun get(seedId: Long): SeedWithApps?

    @Query("SELECT id FROM seed WHERE address = :address")
    suspend fun getIdByAddress(address: String): Long?

    /** Returns the new row id, or -1 if a seed with the same address is already stored. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(seed: SeedEntity): Long

    /**
     * Inserts [seed] and names it after its new id, e.g. "Seed 3". Ids are never reused. If a seed
     * with the same address is already stored, nothing is changed and its id is returned.
     */
    @Transaction
    suspend fun insertWithDefaultName(seed: SeedEntity): Long {
        val id = insert(seed)
        if (id == -1L) return checkNotNull(getIdByAddress(seed.address))
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
