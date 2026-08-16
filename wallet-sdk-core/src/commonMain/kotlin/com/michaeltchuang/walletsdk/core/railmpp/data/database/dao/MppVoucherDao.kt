package com.michaeltchuang.walletsdk.core.railmpp.data.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.michaeltchuang.walletsdk.core.railmpp.data.database.model.MppVoucherEntity

@Dao
internal interface MppVoucherDao {
    @Upsert
    suspend fun upsertVoucher(voucher: MppVoucherEntity)

    @Query("SELECT * FROM mpp_vouchers")
    suspend fun getAllVouchers(): List<MppVoucherEntity>

    @Query("DELETE FROM mpp_vouchers WHERE session_id = :sessionId")
    suspend fun deleteVoucherBySessionId(sessionId: String)
}
