package com.michaeltchuang.walletsdk.core.account.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.michaeltchuang.walletsdk.core.account.data.database.model.PasskeyEntity

@Dao
internal interface PasskeyDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: PasskeyEntity): Long

    @Query("SELECT * FROM passkey_table WHERE credential_id = :credentialId")
    suspend fun getByCredId(credentialId: String): PasskeyEntity?

    @Query("SELECT COUNT(*) FROM passkey_table WHERE site_id = :siteId")
    suspend fun getPasskeyCountBySiteId(siteId: Long): Int

    @Query("DELETE FROM passkey_table WHERE credential_id = :credentialId")
    suspend fun deleteByCredId(credentialId: String): Int

    @Query("DELETE FROM passkey_table")
    suspend fun deleteAll()

    @Query("UPDATE passkey_table SET last_used_time_ms = :lastUsed WHERE credential_id = :credentialId")
    suspend fun updateLastUsedTime(credentialId: String, lastUsed: Long)

    @Query(
        """
            SELECT EXISTS (
            SELECT 1 
            FROM passkey_table 
            INNER JOIN sites ON passkey_table.site_id = sites.id 
            WHERE sites.url = :siteUrl 
                AND passkey_table.user_name = :username
                AND passkey_table.algo_address = :algoAddress
        )
        """
    )
    suspend fun doesPasskeyExist(siteUrl: String, username: String, algoAddress: String): Boolean
}
