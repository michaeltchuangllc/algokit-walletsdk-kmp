package com.michaeltchuang.walletsdk.core.railmpp.data.repository

import com.michaeltchuang.walletsdk.core.railmpp.data.database.dao.MppVoucherDao
import com.michaeltchuang.walletsdk.core.railmpp.data.database.model.MppVoucherEntity
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppVoucherRepository

internal class MppVoucherRepositoryImpl(
    private val mppVoucherDao: MppVoucherDao,
) : MppVoucherRepository {
    override suspend fun upsertVoucher(voucher: MppVoucherEntity) {
        mppVoucherDao.upsertVoucher(voucher)
    }

    override suspend fun getAllVouchers(): List<MppVoucherEntity> = mppVoucherDao.getAllVouchers()

    override suspend fun deleteVoucherByChannelId(channelIdBase64: String) {
        mppVoucherDao.deleteVoucherByChannelId(channelIdBase64)
    }

    override suspend fun deleteSettledVoucher(
        channelIdBase64: String,
        confirmedAmount: Long,
    ) {
        mppVoucherDao.deleteSettledVoucher(channelIdBase64, confirmedAmount)
    }

    override suspend fun deleteVouchersBeforeRound(
        channelIdBase64: String,
        network: String,
        startRound: Long,
    ) {
        mppVoucherDao.deleteVouchersBeforeRound(channelIdBase64, network, startRound)
    }

    override suspend fun deleteVoucherBySessionAndViewer(
        sessionId: String,
        viewerAddress: String,
    ) {
        mppVoucherDao.deleteVoucherBySessionAndViewer(sessionId, viewerAddress)
    }

    override suspend fun deleteVoucherBySessionId(sessionId: String) {
        mppVoucherDao.deleteVoucherBySessionId(sessionId)
    }
}
