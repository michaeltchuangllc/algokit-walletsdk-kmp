package com.michaeltchuang.walletsdk.core

import com.michaeltchuang.walletsdk.core.account.domain.model.custom.AccountLite
import com.michaeltchuang.walletsdk.core.network.model.AccountInformation
import com.michaeltchuang.walletsdk.core.network.model.AlgorandNetwork
import kotlinx.coroutines.flow.Flow

interface WalletSDKManager {
    suspend fun getAccounts(): List<AccountLite>

    suspend fun getAccountInformation(address: String): AccountInformation?

    suspend fun deleteAccount(address: String)

    fun observeCurrentNetwork(): Flow<AlgorandNetwork>

    suspend fun setNetwork(network: AlgorandNetwork)
}
