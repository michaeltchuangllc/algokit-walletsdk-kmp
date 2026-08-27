package com.michaeltchuang.walletsdk.ui.settings.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.account.data.mapper.entity.Algo25AccountTypeMapper
import com.michaeltchuang.walletsdk.core.account.domain.model.core.AccountCreation
import com.michaeltchuang.walletsdk.core.account.domain.repository.local.HdSeedRepository
import com.michaeltchuang.walletsdk.core.algosdk.createAlgo25Account
import com.michaeltchuang.walletsdk.core.deeplink.utils.AssetConstants
import com.michaeltchuang.walletsdk.core.encryption.initializeEncryptionManager
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateViewModel
import com.michaeltchuang.walletsdk.core.foundation.utils.CreationType
import com.michaeltchuang.walletsdk.core.foundation.utils.manager.AccountCreationManager
import com.michaeltchuang.walletsdk.core.network.model.AlgorandNetwork
import com.michaeltchuang.walletsdk.ui.initializeSdk.WalletSDK
import com.michaeltchuang.walletsdk.ui.settings.domain.DebugAddressHolder
import com.michaeltchuang.walletsdk.ui.settings.screens.networkNodeSettings
import kotlinx.coroutines.launch

const val MINIMUM_BALANCE = 10_000_0L // 10 ALGO/USDC

class DeveloperSettingsViewModel(
    private val algo25AccountTypeMapper: Algo25AccountTypeMapper,
    private val hdSeedRepository: HdSeedRepository,
    private val stateDelegate: StateDelegate<ViewState>,
    private val eventDelegate: EventDelegate<ViewEvent>,
) : ViewModel(),
    StateViewModel<DeveloperSettingsViewModel.ViewState> by stateDelegate,
    EventViewModel<DeveloperSettingsViewModel.ViewEvent> by eventDelegate {
    init {
        stateDelegate.setDefaultState(ViewState.Loading)
        viewModelScope.launch { initializeEncryptionManager() }
        hasAnySeedExist()
    }

    private fun hasAnySeedExist() {
        viewModelScope.launch {
            hdSeedRepository.hasAnySeed().let { hasAnySeed ->
                stateDelegate.updateState {
                    ViewState.Content(hasAnySeed)
                }
            }
        }
    }

    fun createAlgoAccount() {
        viewModelScope.launch {
            try {
                createAlgo25Account()?.let {
                    val accountCreation =
                        AccountCreation(
                            address = it.address,
                            customName = null,
                            isBackedUp = false,
                            type = algo25AccountTypeMapper(it.secretKey),
                            creationType = CreationType.CREATE,
                        )
                    // Store the account creation data in the manager
                    AccountCreationManager.storePendingAccountCreation(accountCreation = accountCreation)
                    eventDelegate.sendEvent(ViewEvent.AccountCreated(accountCreation = accountCreation))
                } ?: run {
                    displayError("Failed to create account")
                }
            } catch (e: Exception) {
                displayError(e.message ?: "Unknown error")
            }
        }
    }

    suspend fun checkBalancesAndNavigateToDebugTool(): Boolean {
        return try {
            val currentNetwork = networkNodeSettings.value
            val usdcAssetId =
                when (currentNetwork) {
                    AlgorandNetwork.MAINNET -> AssetConstants.USDC_MAINNET_ID
                    AlgorandNetwork.TESTNET -> AssetConstants.USDC_TESTNET_ID
                    AlgorandNetwork.FUTURENET -> AssetConstants.USDC_FUTURENET_ID
                    else -> AssetConstants.USDC_TESTNET_ID
                }

            val creatorAddress = DebugAddressHolder.creatorAddress
            val viewerAddresses =
                listOf(
                    DebugAddressHolder.viewerAddress,
                    DebugAddressHolder.viewerAddress2,
                    DebugAddressHolder.viewerAddress3,
                ).filter { it.isNotBlank() }

            if (creatorAddress.isBlank() || viewerAddresses.isEmpty()) {
                displayError("Please select creator and at least one viewer in Escrow Session Vault Debug Tool first.")
                return false
            }

            val allAddresses = (viewerAddresses + creatorAddress).distinct()
            val accountsWithAlgo = WalletSDK.getAccountsWithBalances()

            for (address in allAddresses) {
                val account = accountsWithAlgo.find { it.address == address }
                val algoBalance = account?.balance?.toLongOrNull() ?: 0L
                if (algoBalance < MINIMUM_BALANCE) {
                    displayError("Account $address needs at least 10 ALGO.")
                    return false
                }

                val usdcBalanceStr = WalletSDK.getAccountASABalance(address, usdcAssetId)
                val usdcBalance = usdcBalanceStr?.toLongOrNull() ?: 0L
                if (usdcBalance < MINIMUM_BALANCE) {
                    displayError("Account $address needs at least 10 USDC.")
                    return false
                }
            }
            true
        } catch (e: Exception) {
            displayError("Balance check failed: ${e.message}")
            false
        }
    }

    private fun displayError(message: String) {
        viewModelScope.launch {
            eventDelegate.sendEvent(ViewEvent.Error(message))
        }
    }

    sealed interface ViewState {
        data object Idle : ViewState

        data object Loading : ViewState

        data class Content(
            val hasAnySeed: Boolean,
        ) : ViewState
    }

    sealed interface ViewEvent {
        data class AccountCreated(
            val accountCreation: AccountCreation,
        ) : ViewEvent

        data class Error(
            val message: String,
        ) : ViewEvent
    }
}
