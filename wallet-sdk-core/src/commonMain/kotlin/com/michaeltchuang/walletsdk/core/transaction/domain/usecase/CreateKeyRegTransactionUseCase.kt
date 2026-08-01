package com.michaeltchuang.walletsdk.core.transaction.domain.usecase

import com.ionspin.kotlin.bignum.integer.BigInteger
import com.ionspin.kotlin.bignum.integer.toBigInteger
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetLocalAccount
import com.michaeltchuang.walletsdk.core.deeplink.model.KeyRegTransactionDetail
import com.michaeltchuang.walletsdk.core.foundation.utils.MIN_FEE
import com.michaeltchuang.walletsdk.core.foundation.utils.Result
import com.michaeltchuang.walletsdk.core.foundation.utils.Result.Error
import com.michaeltchuang.walletsdk.core.foundation.utils.Result.Success
import com.michaeltchuang.walletsdk.core.network.model.TransactionParams
import com.michaeltchuang.walletsdk.core.network.service.AccountInformationApiService
import com.michaeltchuang.walletsdk.core.network.service.getAccountRekeyAdminAddress
import com.michaeltchuang.walletsdk.core.transaction.model.KeyRegTransaction
import com.michaeltchuang.walletsdk.core.transaction.model.OfflineKeyRegTransactionPayload
import com.michaeltchuang.walletsdk.core.transaction.model.OnlineKeyRegTransactionPayload

interface CreateKeyRegTransaction {
    suspend operator fun invoke(txnDetail: KeyRegTransactionDetail): Result<KeyRegTransaction>
}

internal class CreateKeyRegTransactionUseCase(
    private val getTransactionParams: GetTransactionParams,
    private val buildKeyRegOfflineTransaction: BuildKeyRegOfflineTransaction,
    private val buildKeyRegOnlineTransaction: BuildKeyRegOnlineTransaction,
    private val accountApiService: AccountInformationApiService,
    private val getLocalAccount: GetLocalAccount,
) : CreateKeyRegTransaction {
    override suspend fun invoke(txnDetail: KeyRegTransactionDetail): Result<KeyRegTransaction> =
        when (val params = getTransactionParams()) {
            is Success -> {
                val txnByteArray = createTransactionByteArray(txnDetail, params.data)
                if (txnByteArray == null) {
                    Error(IllegalArgumentException())
                } else {
                    Success(createKeyRegTransactionResult(txnDetail, txnByteArray))
                }
            }

            is Error -> {
                Error(params.exception, params.code)
            }
        }

    private suspend fun createTransactionByteArray(
        txnDetail: KeyRegTransactionDetail,
        params: TransactionParams,
    ): ByteArray? =
        if (txnDetail.isOnlineKeyRegTxn()) {
            buildKeyRegOnlineTransaction(
                params = txnDetail.toOnlineTxnPayload(params = params, flatFee = txnDetail.getFlatFee(params)),
            )
        } else {
            buildKeyRegOfflineTransaction(
                OfflineKeyRegTransactionPayload(
                    txnDetail.address,
                    txnDetail.fee?.toBigInteger(),
                    txnDetail.note,
                    params,
                ),
            )
        }

    private suspend fun createKeyRegTransactionResult(
        txnDetail: KeyRegTransactionDetail,
        txnByteArray: ByteArray,
    ): KeyRegTransaction =
        KeyRegTransaction(
            transactionByteArray = txnByteArray,
            accountAddress = txnDetail.address,
            accountAuthAddress = accountApiService.getAccountRekeyAdminAddress(txnDetail.address),
            isRekeyedToAnotherAccount = false,
        )

    private fun KeyRegTransactionDetail.toOnlineTxnPayload(
        params: TransactionParams,
        flatFee: BigInteger?,
    ): OnlineKeyRegTransactionPayload =
        OnlineKeyRegTransactionPayload(
            senderAddress = address,
            selectionPublicKey = selectionPublicKey.orEmpty(),
            stateProofKey = sprfkey.orEmpty(),
            voteKey = voteKey.orEmpty(),
            voteFirstRound = voteFirstRound.orEmpty(),
            voteLastRound = voteLastRound.orEmpty(),
            voteKeyDilution = voteKeyDilution.orEmpty(),
            txnParams = params,
            note = xnote ?: note,
            flatFee = flatFee,
        )

    private suspend fun KeyRegTransactionDetail.getFlatFee(params: TransactionParams): BigInteger {
        fee?.toLongOrNull()?.takeIf { it > 0L }?.let { return it.toBigInteger() }

        val bundleTransactionCount =
            when (getLocalAccount(address)) {
                is LocalAccount.Falcon24 -> FALCON24_BUNDLE_TXN_COUNT
                else -> 1L
            }
        return (params.getMinimumFee() * bundleTransactionCount).toBigInteger()
    }

    private fun TransactionParams.getMinimumFee(): Long = (minFee ?: MIN_FEE).coerceAtLeast(MIN_FEE)

    private fun KeyRegTransactionDetail.isOnlineKeyRegTxn(): Boolean =
        !voteKey.isNullOrBlank() &&
            !selectionPublicKey.isNullOrBlank() &&
            !voteFirstRound.isNullOrBlank() &&
            !voteLastRound.isNullOrBlank() &&
            !voteKeyDilution.isNullOrBlank()

    private companion object {
        const val FALCON24_BUNDLE_TXN_COUNT = 4L
    }
}
