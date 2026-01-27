package com.michaeltchuang.walletsdk.core.network.service

import com.michaeltchuang.walletsdk.core.network.model.AccountInformation
import com.michaeltchuang.walletsdk.core.network.model.AccountInformationResponse
import com.michaeltchuang.walletsdk.core.network.model.ApiResult

/**
 * Get basic account information excluding heavy data like apps and assets
 */
suspend fun AccountInformationApiService.getBasicAccountInformation(address: String): ApiResult<AccountInformationResponse> =
    getAccountInformation(
        address = address,
        excludes = "apps-local-state,created-apps,assets,created-assets",
    )

/**
 * Get account information with only balance and status
 */
suspend fun AccountInformationApiService.getAccountBalance(address: String): ApiResult<AccountInformationResponse> =
    getAccountInformation(
        address = address,
        excludes = "apps-local-state,created-apps,assets,created-assets,participation",
    )

/**
 * Get complete account information including all fields and closed accounts
 */
suspend fun AccountInformationApiService.getCompleteAccountInformation(address: String): ApiResult<AccountInformationResponse> =
    getAccountInformation(
        address = address,
        excludes = "",
        includeClosedAccounts = true,
    )

/**
 * Check if an account exists (lightweight check)
 */
suspend fun AccountInformationApiService.accountExists(address: String): Boolean =
    when (getBasicAccountInformation(address)) {
        is ApiResult.Success -> true
        is ApiResult.Error -> false
        is ApiResult.NetworkError -> false
    }

/**
 * Get just the account information (unwrapped from the response)
 */
suspend fun AccountInformationApiService.getAccountInformationOnly(
    address: String,
    excludes: String = "",
    includeClosedAccounts: Boolean = false,
): ApiResult<AccountInformation> =
    when (val result = getAccountInformation(address, excludes, includeClosedAccounts)) {
        is ApiResult.Success -> {
            result.data.accountInformation?.let { accountInfo ->
                ApiResult.Success(accountInfo)
            } ?: ApiResult.Error(
                code = 404,
                message = "Account information not found in response",
            )
        }

        is ApiResult.Error -> result
        is ApiResult.NetworkError -> result
    }

/**
 * Get account balance amount directly
 */
suspend fun AccountInformationApiService.getAccountBalanceAmount(address: String): String? =
    when (val result = getAccountBalance(address)) {
        is ApiResult.Success -> result.data.accountInformation?.amount
        else -> null
    }

suspend fun AccountInformationApiService.getAccountRekeyAdminAddress(address: String): String? =
    when (val result = getBasicAccountInformation(address)) {
        is ApiResult.Success -> result.data.accountInformation?.authAddr
        else -> null
    }
