package com.michaeltchuang.walletsdk.ui.signing.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.account.domain.model.core.AccountRegistrationType
import com.michaeltchuang.walletsdk.core.account.domain.model.custom.AccountLite
import com.michaeltchuang.walletsdk.core.account.domain.usecase.core.NameRegistrationUseCase
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetBasicAccountInformationUseCase
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateViewModel
import com.michaeltchuang.walletsdk.ui.initializeSdk.WalletSDK
import kotlinx.coroutines.launch

class SelectAccountViewModel(
    private val nameRegistrationUseCase: NameRegistrationUseCase,
    private val getBasicAccountInformationUseCase: GetBasicAccountInformationUseCase,
    private val stateDelegate: StateDelegate<AccountsState>,
    private val eventDelegate: EventDelegate<ViewEvent>,
) : ViewModel(),
    StateViewModel<SelectAccountViewModel.AccountsState> by stateDelegate,
    EventViewModel<SelectAccountViewModel.ViewEvent> by eventDelegate {
    private var receiverAddress: String = ""
    private var amount: String = ""
    private var assetId: Long = -7L
    private var note: String = ""

    init {
        stateDelegate.setDefaultState(AccountsState.Loading)
    }

    fun setup(
        receiverAddress: String,
        assetId: Long,
        amount: String,
        note: String,
    ) {
        this.receiverAddress = receiverAddress
        this.assetId = assetId
        this.amount = amount
        this.note = note
        fetchAccounts()
    }

    fun fetchAccounts() {
        stateDelegate.updateState { AccountsState.Loading }
        viewModelScope.launch {
            try {

                // Fetch account details for all accounts to get their amounts
                val accountsWithAlgoBalances = WalletSDK.getAccountsWithBalances()
                val accountLite = fetchAndMergeSolanaBalances(accountsWithAlgoBalances)
                stateDelegate.updateState {
                    AccountsState.Content(accountLite)
                }
            } catch (e: Exception) {
                stateDelegate.updateState { AccountsState.Error(e.message ?: "Unknown error") }
                eventDelegate.sendEvent(
                    ViewEvent.ShowError(
                        e.message ?: "Failed to fetch accounts.",
                    ),
                )
            }
        }
    }

    fun onAccountSelected(senderAddress: String) {
        viewModelScope.launch {
            eventDelegate.sendEvent(
                ViewEvent.NavigateToAssetTransfer(
                    senderAddress = senderAddress,
                    receiverAddress = receiverAddress,
                    assetId = assetId,
                    amount = amount,
                    note = note,
                ),
            )
        }
    }

    private suspend fun fetchAndMergeSolanaBalances(accounts: List<AccountLite>): List<AccountLite> {
        val solanaAccounts =
            accounts.filter {
                it.registrationType is AccountRegistrationType.SeedVault
            }
        if (solanaAccounts.isEmpty()) return accounts
        val solanaAddresses = solanaAccounts.map { it.address }
        val balancesByAddress =
            WalletSDK.getSolanaBalances(solanaAddresses)
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
                account.copy(balance = balancesByAddress[account.address] ?: account.balance)
            } else {
                account
            }
        }
    }

    sealed interface AccountsState {
        data object Loading : AccountsState

        data class Content(
            val accounts: List<AccountLite>,
        ) : AccountsState

        data class Error(
            val message: String,
        ) : AccountsState
    }

    sealed interface ViewEvent {
        data class ShowError(
            val message: String,
        ) : ViewEvent

        data class NavigateToAssetTransfer(
            val senderAddress: String,
            val receiverAddress: String,
            val assetId: Long,
            val amount: String,
            val note: String,
        ) : ViewEvent
    }
}
