package com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.account.domain.model.core.AccountRegistrationType
import com.michaeltchuang.walletsdk.core.account.domain.model.custom.AccountLite
import com.michaeltchuang.walletsdk.core.account.domain.usecase.core.NameRegistrationUseCase
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetBasicAccountInformationUseCase
import com.michaeltchuang.walletsdk.core.deeplink.utils.AssetConstants
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateViewModel
import com.michaeltchuang.walletsdk.core.network.model.AlgorandNetwork
import com.michaeltchuang.walletsdk.ui.initializeSdk.WalletSDK
import com.michaeltchuang.walletsdk.ui.liquidAuth.utils.fromUri
import com.michaeltchuang.walletsdk.ui.liquidAuth.utils.getSupportedAccountsByAppId
import com.michaeltchuang.walletsdk.ui.settings.screens.networkNodeSettings
import kotlinx.coroutines.launch

data class AuthMessage(
    val origin: String,
    val requestId: String,
    val appId: String,
)

class LiquidAuthViewModel(
    private val nameRegistrationUseCase: NameRegistrationUseCase,
    private val getBasicAccountInformationUseCase: GetBasicAccountInformationUseCase,
    private val stateDelegate: StateDelegate<ViewState>,
    private val eventDelegate: EventDelegate<ViewEvent>,
) : ViewModel(),
    StateViewModel<LiquidAuthViewModel.ViewState> by stateDelegate,
    EventViewModel<LiquidAuthViewModel.ViewEvent> by eventDelegate {
    var authMessage: AuthMessage? = null
    private var currentNetwork: AlgorandNetwork? = null

    init {
        stateDelegate.setDefaultState(ViewState.Idle)
        // Listen for network changes and refetch accounts when network changes
        viewModelScope.launch {
            networkNodeSettings.collect { network ->
                currentNetwork = network
                authMessage?.let {
                    fetchAccounts(it)
                }

            }
        }
    }

    private fun fetchAccounts(authMessage: AuthMessage) {
        stateDelegate.updateState { ViewState.Loading }
        viewModelScope.launch {
            try {
                // Fetch account details for all accounts to get their amounts
                val accountsWithAlgoBalances = WalletSDK.getAccountsWithBalances()
                val accountLite = fetchAndMergeSolanaBalances(accountsWithAlgoBalances)

                val accounts = getSupportedAccountsByAppId(authMessage.appId,accountLite)

                stateDelegate.updateState {
                    ViewState.Content(accounts)
                }
            } catch (e: Exception) {
                stateDelegate.updateState { ViewState.Error(e.message ?: "Unknown error") }
                eventDelegate.sendEvent(
                    ViewEvent.ShowError(
                        e.message ?: "Failed to fetch accounts.",
                    ),
                )
            }
        }
    }

    fun initialize(uri: String?) {
        viewModelScope.launch {
            if (uri.isNullOrEmpty()) {
                stateDelegate.updateState {
                    ViewState.Error("No URI provided")
                }
                return@launch
            }

            stateDelegate.updateState {
                ViewState.Loading
            }

            try {
                authMessage = fromUri(uri)
                authMessage?.let { fetchAccounts(it) }

            } catch (e: Exception) {
                stateDelegate.updateState {
                    ViewState.Error("Failed to parse URI: ${e.message}")
                }
            }
        }
    }

    private suspend fun fetchAndMergeSolanaBalances(accounts: List<AccountLite>): List<AccountLite> {
        val usdcAssetId =
            when (currentNetwork) {
                AlgorandNetwork.MAINNET -> AssetConstants.USDC_MAINNET_ID
                else -> AssetConstants.USDC_TESTNET_ID
            }
        val solanaAccounts =
            accounts.filter {
                it.registrationType is AccountRegistrationType.SeedVault
            }
        val nonSolanaAccounts =
            accounts.filterNot {
                it.registrationType is AccountRegistrationType.SeedVault
            }
        if (solanaAccounts.isEmpty()) {
            return nonSolanaAccounts.map { account ->
                val usdcBalance = WalletSDK.getAccountASABalance(account.address, usdcAssetId)
                account.copy(usdcBalance = usdcBalance ?: account.usdcBalance)
            }
        }
        val solanaAddresses = solanaAccounts.map { it.address }
        val balancesByAddress =
            WalletSDK.getSolanaBalances(solanaAddresses)
        val usdcBalancesByAddress =
            WalletSDK.getSolanaUsdcBalances(solanaAddresses)
        val asaUsdcBalancesByAddress =
            nonSolanaAccounts.associate { account ->
                account.address to WalletSDK.getAccountASABalance(account.address, usdcAssetId)
            }
        val failedCount = balancesByAddress.count { it.value == null }
        if (failedCount > 0 && failedCount == solanaAccounts.size) {
            eventDelegate.sendEvent(
                ViewEvent.ShowError(
                    "Failed to fetch Solana balances on DEVNET.",
                ),
            )
        }
        return accounts.map { account ->
            if (account.registrationType is AccountRegistrationType.SeedVault) {
                account.copy(
                    balance = balancesByAddress[account.address] ?: account.balance,
                    usdcBalance = usdcBalancesByAddress[account.address] ?: account.usdcBalance,
                )
            } else {
                account.copy(
                    usdcBalance = asaUsdcBalancesByAddress[account.address] ?: account.usdcBalance,
                )
            }
        }
    }

    sealed interface ViewState {
        data object Idle : ViewState

        data object Loading : ViewState

        data class Content(
            val accounts: List<AccountLite>,
        ) : ViewState

        data class Error(
            val message: String,
        ) : ViewState
    }

    sealed interface ViewEvent {
        data object AuthenticationSuccess : ViewEvent

        data class ShowError(
            val message: String,
        ) : ViewEvent
    }
}
