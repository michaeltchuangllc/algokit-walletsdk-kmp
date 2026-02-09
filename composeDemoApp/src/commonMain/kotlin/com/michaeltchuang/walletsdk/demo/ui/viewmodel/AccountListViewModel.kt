package com.michaeltchuang.walletsdk.demo.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.account.domain.model.custom.AccountLite
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateViewModel
import com.michaeltchuang.walletsdk.core.network.model.AlgorandNetwork
import com.michaeltchuang.walletsdk.ui.initializeSdk.WalletSDK
import com.michaeltchuang.walletsdk.ui.settings.screens.networkNodeSettings
import kotlinx.coroutines.launch

class AccountListViewModel(
    private val stateDelegate: StateDelegate<AccountsState>,
    private val eventDelegate: EventDelegate<AccountsEvent>,
) : ViewModel(),
    StateViewModel<AccountListViewModel.AccountsState> by stateDelegate,
    EventViewModel<AccountListViewModel.AccountsEvent> by eventDelegate {
    var accountLite = emptyList<AccountLite>()
    private var currentNetwork: AlgorandNetwork? = null

    init {
        stateDelegate.setDefaultState(AccountsState.Idle)

        // Listen for network changes and refetch accounts when network changes
        viewModelScope.launch {
            networkNodeSettings.collect { network ->
                fetchAccounts()
            }
        }
    }

    fun fetchAccounts() {
        stateDelegate.updateState { AccountsState.Loading }
        viewModelScope.launch {
            try {
                // Fetch all accounts with their current balances
                accountLite = WalletSDK.getAccountsWithBalances()

                stateDelegate.updateState {
                    AccountsState.Content(accountLite)
                }
            } catch (e: Exception) {
                stateDelegate.updateState { AccountsState.Error(e.message ?: "Unknown error") }
                eventDelegate.sendEvent(
                    AccountsEvent.ShowError(
                        e.message ?: "Failed to fetch accounts.",
                    ),
                )
            }
        }
    }

    fun deleteAccount(address: String) {
        stateDelegate.updateState { AccountsState.Loading }
        viewModelScope.launch {
            try {
                WalletSDK.deleteAccount(address)
                eventDelegate.sendEvent(AccountsEvent.ShowMessage("Account deleted successfully."))
                fetchAccounts() // Refresh the list after deletion
            } catch (e: Exception) {
                stateDelegate.updateState { AccountsState.Error(e.message ?: "Unknown error") }
                eventDelegate.sendEvent(
                    AccountsEvent.ShowError(
                        e.message ?: "Failed to delete account.",
                    ),
                )
            }
        }
    }

    sealed interface AccountsState {
        data object Idle : AccountsState

        data object Loading : AccountsState

        data class Content(
            val accounts: List<AccountLite>,
        ) : AccountsState

        data class Error(
            val message: String,
        ) : AccountsState
    }

    sealed interface AccountsEvent {
        data class ShowError(
            val message: String,
        ) : AccountsEvent

        data class ShowMessage(
            val message: String,
        ) : AccountsEvent
    }
}
