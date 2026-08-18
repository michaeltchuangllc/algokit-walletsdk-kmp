package com.michaeltchuang.walletsdk.core.railmpp.domain.repository

import com.michaeltchuang.walletsdk.core.railmpp.data.database.model.MppVoucherEntity

interface MppVoucherRepository {
    suspend fun upsertVoucher(voucher: MppVoucherEntity)
    suspend fun getAllVouchers(): List<MppVoucherEntity>
    suspend fun deleteVoucherByChannelId(channelIdBase64: String)
    suspend fun deleteVoucherBySessionAndViewer(
        sessionId: String,
        viewerAddress: String,
    )
    suspend fun deleteVoucherBySessionId(sessionId: String)
}

/**
 * Deletes the voucher record identified by the given [sessionId]/[viewerAddress]/[channelIdBase64],
 * preferring [channelIdBase64] since it's the primary key and uniquely identifies the voucher's
 * payment channel. [sessionId]/[viewerAddress] are no longer unique on their own (multiple
 * viewers may share a liquid-auth requestId, and therefore a sessionId), so they're only used as
 * a fallback when a channel id isn't available.
 */
suspend fun MppVoucherRepository.deleteVoucher(
    sessionId: String,
    viewerAddress: String?,
    channelIdBase64: String?,
) {
    when {
        !channelIdBase64.isNullOrBlank() -> deleteVoucherByChannelId(channelIdBase64)
        !viewerAddress.isNullOrBlank() -> deleteVoucherBySessionAndViewer(sessionId, viewerAddress)
        else -> deleteVoucherBySessionId(sessionId)
    }
}
