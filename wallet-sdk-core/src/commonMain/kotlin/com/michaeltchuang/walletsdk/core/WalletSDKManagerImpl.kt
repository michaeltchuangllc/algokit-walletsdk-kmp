package com.michaeltchuang.walletsdk.core

import com.michaeltchuang.walletsdk.core.account.domain.model.custom.AccountLite
import com.michaeltchuang.walletsdk.core.account.domain.usecase.core.NameRegistrationUseCase
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetBasicAccountInformationUseCase
import com.michaeltchuang.walletsdk.core.network.domain.usecase.GetCurrentNetworkUseCase
import com.michaeltchuang.walletsdk.core.network.domain.usecase.SaveNetworkPreferenceUseCase
import com.michaeltchuang.walletsdk.core.network.model.AccountInformation
import com.michaeltchuang.walletsdk.core.network.model.AlgorandNetwork
import kotlinx.coroutines.flow.Flow
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

internal class WalletSDKManagerImpl : WalletSDKManager, KoinComponent {
    private val nameRegistrationUseCase: NameRegistrationUseCase by inject()
    private val getBasicAccountInformationUseCase: GetBasicAccountInformationUseCase by inject()
    private val getCurrentNetworkUseCase: GetCurrentNetworkUseCase by inject()
    private val saveNetworkPreferenceUseCase: SaveNetworkPreferenceUseCase by inject()

    override suspend fun getAccounts(): List<AccountLite> {
        return nameRegistrationUseCase.getAccountLite()
    }

    override suspend fun getAccountInformation(address: String): AccountInformation? {
        return getBasicAccountInformationUseCase(address)
    }

    override suspend fun deleteAccount(address: String) {
        nameRegistrationUseCase.deleteAccount(address)
    }

    override fun observeCurrentNetwork(): Flow<AlgorandNetwork> {
        return getCurrentNetworkUseCase()
    }

    override suspend fun setNetwork(network: AlgorandNetwork) {
        saveNetworkPreferenceUseCase(network)
    }
}
