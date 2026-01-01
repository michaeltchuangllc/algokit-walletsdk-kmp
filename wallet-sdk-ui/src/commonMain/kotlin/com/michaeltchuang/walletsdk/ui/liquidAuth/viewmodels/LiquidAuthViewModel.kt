package com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.account.domain.model.custom.AccountLite
import com.michaeltchuang.walletsdk.core.account.domain.usecase.core.NameRegistrationUseCase
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetBasicAccountInformationUseCase
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateViewModel
import com.michaeltchuang.walletsdk.ui.liquidAuth.utils.fromUri
import com.michaeltchuang.walletsdk.ui.signing.viewmodels.SelectAccountViewModel
import com.michaeltchuang.walletsdk.ui.signing.viewmodels.SelectAccountViewModel.AccountsState
import kotlinx.coroutines.launch

data class AuthMessage(
    val origin: String,
    val requestId: String,
)

class LiquidAuthViewModel(
    private val nameRegistrationUseCase: NameRegistrationUseCase,
    private val getBasicAccountInformationUseCase: GetBasicAccountInformationUseCase,
    private val stateDelegate: StateDelegate<ViewState>,
    private val eventDelegate: EventDelegate<ViewEvent>,
) : ViewModel(),
    StateViewModel<LiquidAuthViewModel.ViewState> by stateDelegate,
    EventViewModel<LiquidAuthViewModel.ViewEvent> by eventDelegate {
    lateinit var authMessage: AuthMessage

    init {
        stateDelegate.setDefaultState(ViewState.Idle)
    }

    fun fetchAccounts() {
        stateDelegate.updateState { ViewState.Loading }
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
                    ViewState.Content(accountLite)
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
        fetchAccounts()
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
            } catch (e: Exception) {
                stateDelegate.updateState {
                    ViewState.Error("Failed to parse URI: ${e.message}")
                }
            }
        }
    }

    fun processAuthentication() {
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
