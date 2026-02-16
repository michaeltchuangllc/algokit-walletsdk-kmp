package com.michaeltchuang.walletsdk.core.account.domain.usecase.core

import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import com.michaeltchuang.walletsdk.core.account.domain.model.local.TransactionFee
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetLocalAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetTransactionFeeForAccount
internal class GetTransactionFeeForAccountUseCase(
    private val getLocalAccount: GetLocalAccount,
) : GetTransactionFeeForAccount {
    override suspend fun invoke(address: String): TransactionFee {
        val account = getLocalAccount(address)
        return when (account) {
            is LocalAccount.Falcon24 -> TransactionFee(
                feeInMicroAlgos = TransactionFee.FALCON24_FEE_MICRO_ALGOS,
                feeInAlgos = TransactionFee.FALCON24_FEE_ALGOS,
            )
            else -> TransactionFee(
                feeInMicroAlgos = TransactionFee.STANDARD_FEE_MICRO_ALGOS,
                feeInAlgos = TransactionFee.STANDARD_FEE_ALGOS,
            )
        }
    }
}
