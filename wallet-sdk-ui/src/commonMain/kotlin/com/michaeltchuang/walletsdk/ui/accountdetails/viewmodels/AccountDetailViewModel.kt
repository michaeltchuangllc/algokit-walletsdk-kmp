package com.michaeltchuang.walletsdk.ui.accountdetails.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.core.GetAccountASABalance
import com.michaeltchuang.walletsdk.core.account.domain.usecase.core.NameRegistrationUseCase
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetLocalAccount
import com.michaeltchuang.walletsdk.core.deeplink.utils.AssetConstants
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateViewModel
import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants.FUTURENET_DISPENSER_BASE_URL
import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants.TESTNET_DISPENSER_BASE_URL
import com.michaeltchuang.walletsdk.core.network.domain.usecase.GetCurrentNetworkUseCase
import com.michaeltchuang.walletsdk.core.network.domain.usecase.GetTransactionHistoryUrlUseCase
import com.michaeltchuang.walletsdk.core.network.model.AlgorandNetwork
import kotlinx.coroutines.launch

class AccountDetailViewModel(
    private val nameRegistrationUseCase: NameRegistrationUseCase,
    private val getCurrentNetworkUseCase: GetCurrentNetworkUseCase,
    private val getTransactionHistoryUrlUseCase: GetTransactionHistoryUrlUseCase,
    private val getLocalAccount: GetLocalAccount,
    private val getAccountASABalance: GetAccountASABalance,
    private val stateDelegate: StateDelegate<ViewState>,
    private val eventDelegate: EventDelegate<ViewEvent>,
) : ViewModel(),
    StateViewModel<AccountDetailViewModel.ViewState> by stateDelegate,
    EventViewModel<AccountDetailViewModel.ViewEvent> by eventDelegate {
    private var currentAddress: String? = null

    init {
        stateDelegate.setDefaultState(ViewState.Loading)
    }

    fun loadAccountDetails(address: String) {
        currentAddress = address
        loadAccountState(address)
    }

    private fun loadAccountState(address: String) {
        viewModelScope.launch {
            try {
                // Get account information
                val localAccount = getLocalAccount(address)
                val isNoAuthAccount =
                    localAccount is LocalAccount.NoAuth || localAccount is LocalAccount.SeedVault
                val isSolanaAccount = localAccount is LocalAccount.SeedVault

                getCurrentNetworkUseCase().collect { network ->
                    val usdcAssetId =
                        when (network) {
                            AlgorandNetwork.MAINNET -> AssetConstants.USDC_MAINNET_ID
                            AlgorandNetwork.TESTNET -> AssetConstants.USDC_TESTNET_ID
                            AlgorandNetwork.FUTURENET -> AssetConstants.USDC_FUTURENET_ID
                        }
                    val isUsdcOptedIn = getAccountASABalance(address, usdcAssetId) != null
                    stateDelegate.updateState {
                        ViewState.Content(
                            currentNetwork = network,
                            isTestNet = network == AlgorandNetwork.TESTNET,
                            dispenserBaseUrl =
                                when (network) {
                                    AlgorandNetwork.TESTNET -> TESTNET_DISPENSER_BASE_URL
                                    AlgorandNetwork.FUTURENET -> FUTURENET_DISPENSER_BASE_URL
                                    AlgorandNetwork.MAINNET -> null
                                },
                            transactionHistoryUrl =
                                getTransactionHistoryUrlUseCase(
                                    address = address,
                                    network = network,
                                    isSolanaAccount = isSolanaAccount,
                                ),
                            isNoAuthAccount = isNoAuthAccount,
                            isSolanaAccount = isSolanaAccount,
                            isUsdcOptedIn = isUsdcOptedIn,
                        )
                    }
                }
            } catch (e: Exception) {
                eventDelegate.sendEvent(
                    ViewEvent.Error(e.message ?: "Failed to load account details"),
                )
            }
        }
    }

    fun deleteAccount(address: String) {
        viewModelScope.launch {
            try {
                nameRegistrationUseCase.deleteAccount(address)
                eventDelegate.sendEvent(ViewEvent.AccountDeleted("Account deleted successfully."))
            } catch (e: Exception) {
                eventDelegate.sendEvent(
                    ViewEvent.Error(
                        e.message ?: "Failed to delete account.",
                    ),
                )
            }
        }
    }

    sealed interface ViewState {
        data object Idle : ViewState

        data object Loading : ViewState

        data class Content(
            val currentNetwork: AlgorandNetwork,
            val isTestNet: Boolean,
            val dispenserBaseUrl: String? = null,
            val transactionHistoryUrl: String = "",
            val isNoAuthAccount: Boolean = false,
            val isSolanaAccount: Boolean = false,
            val isUsdcOptedIn: Boolean = false,
        ) : ViewState
    }

    sealed interface ViewEvent {
        data class Error(
            val message: String,
        ) : ViewEvent

        data class AccountDeleted(
            val message: String,
        ) : ViewEvent
    }
}
