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

    @Query("DELETE FROM mpp_vouchers WHERE channel_id_base64 = :channelIdBase64")
    suspend fun deleteVoucherByChannelId(channelIdBase64: String)

    @Query("DELETE FROM mpp_vouchers WHERE session_id = :sessionId AND viewer_address = :viewerAddress")
    suspend fun deleteVoucherBySessionAndViewer(
        sessionId: String,
        viewerAddress: String,
    )

    @Query("DELETE FROM mpp_vouchers WHERE session_id = :sessionId")
    suspend fun deleteVoucherBySessionId(sessionId: String)
}
