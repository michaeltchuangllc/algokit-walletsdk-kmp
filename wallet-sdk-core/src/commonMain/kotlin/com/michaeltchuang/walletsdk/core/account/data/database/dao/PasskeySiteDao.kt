package com.michaeltchuang.walletsdk.core.account.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.michaeltchuang.walletsdk.core.account.data.database.model.SiteEntity
import com.michaeltchuang.walletsdk.core.account.data.database.model.SiteWithPasskeysQuery
import kotlinx.coroutines.flow.Flow

@Dao
internal interface PasskeySiteDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: SiteEntity): Long

    @Transaction
    @Query("SELECT * FROM sites ORDER BY url")
    fun getPasskeysAsFlow(): Flow<List<SiteWithPasskeysQuery>>

    @Transaction
    @Query("SELECT * FROM sites WHERE url = :url ORDER BY url")
    suspend fun getPasskeysByUrl(url: String): List<SiteWithPasskeysQuery>

    @Transaction
    @Query("SELECT * FROM sites WHERE id = :id ORDER BY url")
    suspend fun getPasskeysById(id: Long): List<SiteWithPasskeysQuery>

    @Query("DELETE FROM sites WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT id FROM sites WHERE url = :url")
    suspend fun getSiteId(url: String): Long?

    @Query("SELECT * FROM sites WHERE id = :id")
    suspend fun getSiteById(id: Long): SiteEntity?

    @Query("DELETE FROM sites")
    suspend fun deleteAll()
}
