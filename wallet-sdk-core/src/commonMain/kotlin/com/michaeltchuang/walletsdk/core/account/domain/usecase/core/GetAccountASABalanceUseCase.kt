package com.michaeltchuang.walletsdk.core.account.domain.usecase.core

import com.ionspin.kotlin.bignum.integer.BigInteger
import com.ionspin.kotlin.bignum.integer.toBigInteger
import com.michaeltchuang.walletsdk.core.network.model.ApiResult
import com.michaeltchuang.walletsdk.core.network.service.AccountInformationApiService
import com.michaeltchuang.walletsdk.core.network.service.getAccountBalance

fun interface GetAccountASABalance {
    suspend operator fun invoke(
        address: String,
        assetId: Long,
    ): BigInteger?
}

internal class GetAccountASABalanceUseCase(
    private val accountInformationApiService: AccountInformationApiService,
) : GetAccountASABalance {
    override suspend fun invoke(
        address: String,
        assetId: Long,
    ): BigInteger? =
        when (
            val result =
                accountInformationApiService.getAccountBalance(address)
        ) {
            is ApiResult.Success -> {
                result.data.accountInformation?.assets?.let { assets ->
                    assets
                        .find { it.assetId == assetId }
                        ?.amount
                        ?.toBigInteger()
                }
            }

            is ApiResult.Error -> null
            is ApiResult.NetworkError -> null
        }
}
