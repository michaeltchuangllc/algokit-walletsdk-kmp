package com.michaeltchuang.walletsdk.core.railmpp.data.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.Transaction
import com.michaeltchuang.walletsdk.core.railmpp.data.database.model.MppVoucherEntity

@Dao
internal interface MppVoucherDao {
    @Upsert
    suspend fun writeVoucher(voucher: MppVoucherEntity)

    @Query("SELECT * FROM mpp_vouchers WHERE channel_id_base64 = :channelId")
    suspend fun getVoucher(channelId: String): MppVoucherEntity?

    @Transaction
    suspend fun upsertVoucher(voucher: MppVoucherEntity) {
        val previous = getVoucher(voucher.channelIdBase64)
        // A reconnect must not replace a newer cumulative authorization or another network.
        require(previous?.network == null || previous.network == voucher.network) { "Voucher network mismatch" }
        if (previous == null || voucher.totalAmountClaimedMicroUsdc > previous.totalAmountClaimedMicroUsdc) {
            writeVoucher(voucher)
        }
    }

    @Query("SELECT * FROM mpp_vouchers")
    suspend fun getAllVouchers(): List<MppVoucherEntity>

    @Query("DELETE FROM mpp_vouchers WHERE channel_id_base64 = :channelIdBase64")
    suspend fun deleteVoucherByChannelId(channelIdBase64: String)

    @Query("DELETE FROM mpp_vouchers WHERE channel_id_base64 = :channelIdBase64 AND total_amount_claimed_micro_usdc <= :confirmedAmount")
    suspend fun deleteSettledVoucher(channelIdBase64: String, confirmedAmount: Long)

    @Query("DELETE FROM mpp_vouchers WHERE channel_id_base64 = :channelIdBase64 AND network = :network AND block_number > 0 AND block_number < :startRound")
    suspend fun deleteVouchersBeforeRound(channelIdBase64: String, network: String, startRound: Long)

    @Query("DELETE FROM mpp_vouchers WHERE session_id = :sessionId AND viewer_address = :viewerAddress")
    suspend fun deleteVoucherBySessionAndViewer(
        sessionId: String,
        viewerAddress: String,
    )

    @Query("DELETE FROM mpp_vouchers WHERE session_id = :sessionId")
    suspend fun deleteVoucherBySessionId(sessionId: String)
}
