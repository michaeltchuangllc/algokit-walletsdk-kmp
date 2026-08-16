package com.michaeltchuang.walletsdk.core.railmpp.domain.repository

import com.michaeltchuang.walletsdk.core.railmpp.data.database.model.MppVoucherEntity

interface MppVoucherRepository {
    suspend fun upsertVoucher(voucher: MppVoucherEntity)
    suspend fun getAllVouchers(): List<MppVoucherEntity>
    suspend fun deleteVoucherBySessionId(sessionId: String)
}
