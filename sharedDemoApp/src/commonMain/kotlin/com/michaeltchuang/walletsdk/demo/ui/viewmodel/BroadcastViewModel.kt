package com.michaeltchuang.walletsdk.demo.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.core.GetLocalAccountsUseCase
import com.michaeltchuang.walletsdk.core.deeplink.utils.AssetConstants
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateViewModel
import com.michaeltchuang.walletsdk.core.foundation.utils.AppId
import com.michaeltchuang.walletsdk.core.network.domain.usecase.GetCurrentNetworkUseCase
import com.michaeltchuang.walletsdk.core.network.model.AlgorandNetwork
import com.michaeltchuang.walletsdk.ui.liquidAuth.utils.getSupportedLocalAccountsByAppId
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class BroadcastViewModel(
    private val stateDelegate: StateDelegate<BroadcastState>,
    private val getLocalAccounts: GetLocalAccountsUseCase,
    private val getCurrentNetwork: GetCurrentNetworkUseCase,
) : ViewModel(),
    StateViewModel<BroadcastViewModel.BroadcastState> by stateDelegate {
    init {
        stateDelegate.setDefaultState(BroadcastState())
        loadAccounts()
        observeNetwork()
    }

    fun onEvent(event: BroadcastEvent) {
        when (event) {
            is BroadcastEvent.CreatorAddressSelected -> selectCreatorAddress(event.address)
        }
    }

    private fun loadAccounts() {
        viewModelScope.launch {
            val accounts =
                getSupportedLocalAccountsByAppId(
                    appId = AppId.LIQUID_AUTH_STREAM.name,
                    localAccount = getLocalAccounts(),
                )
            updateAccounts(accounts)
        }
    }

    private fun observeNetwork() {
        viewModelScope.launch {
            getCurrentNetwork().collectLatest { network ->
                stateDelegate.updateState {
                    it.copy(creatorAssetId = network.usdcAssetId)
                }
            }
        }
    }

    private fun updateAccounts(accounts: List<LocalAccount>) {
        stateDelegate.updateState { currentState ->
            val selectedCreatorAddress = currentState.selectedCreatorAddress
            currentState.copy(
                accounts = accounts,
                accountsLoaded = true,
                selectedCreatorAddress =
                    selectedCreatorAddress?.takeIf { selectedAddress ->
                        accounts.any { it.address == selectedAddress }
                    } ?: accounts.firstOrNull()?.address,
            )
        }
    }

    private fun selectCreatorAddress(address: String) {
        stateDelegate.updateState { currentState ->
            if (currentState.accounts.any { it.address == address }) {
                currentState.copy(selectedCreatorAddress = address)
            } else {
                currentState
            }
        }
    }

    data class BroadcastState(
        val accountsLoaded: Boolean = false,
        val accounts: List<LocalAccount> = emptyList(),
        val selectedCreatorAddress: String? = null,
        val creatorAssetId: Long = AssetConstants.USDC_TESTNET_ID,
    )

    sealed interface BroadcastEvent {
        data class CreatorAddressSelected(
            val address: String,
        ) : BroadcastEvent
    }

    private val AlgorandNetwork.usdcAssetId: Long
        get() =
            when (this) {
                AlgorandNetwork.MAINNET -> AssetConstants.USDC_MAINNET_ID
                AlgorandNetwork.TESTNET -> AssetConstants.USDC_TESTNET_ID
                AlgorandNetwork.FUTURENET -> AssetConstants.USDC_FUTURENET_ID
            }
}
