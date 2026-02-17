package com.michaeltchuang.walletsdk.ui.signing.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.account.domain.model.custom.AccountLite
import com.michaeltchuang.walletsdk.core.account.domain.usecase.core.NameRegistrationUseCase
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetBasicAccountInformationUseCase
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateViewModel
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
    private var note: String = ""

    init {
        stateDelegate.setDefaultState(AccountsState.Loading)
    }

    fun setup(
        receiverAddress: String,
        amount: String,
        note: String,
    ) {
        this.receiverAddress = receiverAddress
        this.amount = amount
        this.note = note
        fetchAccounts()
    }

    fun fetchAccounts() {
        stateDelegate.updateState { AccountsState.Loading }
        viewModelScope.launch {
            try {
                var accountLite =
                    nameRegistrationUseCase.getAccountLite()

                // Fetch account details for all accounts to get their amounts
                val accountsWithAmounts =
                    accountLite.map { account ->
                        val accountInfo = getBasicAccountInformationUseCase(account.address)
                        account.copy(balance = accountInfo?.amount ?: "0")
                    }

                accountLite = accountsWithAmounts
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
                    amount = amount,
                    note = note,
                ),
            )
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
            val amount: String,
            val note: String,
        ) : ViewEvent
    }
}
