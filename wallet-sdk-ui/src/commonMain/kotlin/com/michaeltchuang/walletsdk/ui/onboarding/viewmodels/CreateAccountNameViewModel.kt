package com.michaeltchuang.walletsdk.ui.onboarding.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.account.domain.model.core.AccountCreation
import com.michaeltchuang.walletsdk.core.account.domain.usecase.core.NameRegistrationUseCase
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetMaxHdSeedId
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateViewModel
import com.michaeltchuang.walletsdk.core.foundation.utils.manager.AccountCreationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch

class CreateAccountNameViewModel(
    private val nameRegistrationUseCase: NameRegistrationUseCase,
    private val getMaxHdSeedId: GetMaxHdSeedId,
    private val stateDelegate: StateDelegate<ViewState>,
    private val eventDelegate: EventDelegate<ViewEvent>,
) : ViewModel(),
    StateViewModel<CreateAccountNameViewModel.ViewState> by stateDelegate,
    EventViewModel<CreateAccountNameViewModel.ViewEvent> by eventDelegate {
    init {
        stateDelegate.setDefaultState(ViewState.Idle)
    }

    private var walletId: Int? = null

    fun addNewAccount(
        accountCreation: AccountCreation,
        customName: String? = null,
    ) {
        val updatedAccountCreation =
            customName?.let {
                accountCreation.copy(customName = it)
            } ?: accountCreation
        viewModelScope.launch {
            try {
                nameRegistrationUseCase.addNewAccount(updatedAccountCreation)
                AccountCreationManager.clearPendingAccountCreation()
                eventDelegate.sendEvent(ViewEvent.FinishedAccountCreation)
            } catch (e: Exception) {
                displayError(e.message ?: "Unknown error")
            }
        }
    }

    private suspend fun displayError(message: String) {
        eventDelegate.sendEvent(ViewEvent.Error(message))
    }

    fun fetchAccountDetails(accountCreation: AccountCreation) {
        when (val type = accountCreation.type) {
            is AccountCreation.Type.Algo25 -> handleAlgo25Account()
            is AccountCreation.Type.Falcon24 -> handleFalcon24Account(seedId = type.seedId)
            is AccountCreation.Type.HdKey -> handleHDAccount(seedId = type.seedId)
            is AccountCreation.Type.LedgerBle, is AccountCreation.Type.NoAuth -> Unit
        }
    }

    private fun handleHDAccount(seedId: Int?) {
        viewModelScope.launch(Dispatchers.IO) {
            val resolvedId = seedId ?: ((getMaxHdSeedId.invoke() ?: 0) + 1)
            walletId = resolvedId
            stateDelegate.updateState { ViewState.Content(walletId) }
        }
    }

    private fun handleFalcon24Account(seedId: Int?) {
        viewModelScope.launch(Dispatchers.IO) {
            val resolvedId = seedId ?: ((getMaxHdSeedId.invoke() ?: 0) + 1)
            walletId = resolvedId
            stateDelegate.updateState { ViewState.Content(walletId) }
        }
    }

    private fun handleAlgo25Account() {
        stateDelegate.updateState { ViewState.Content() }
    }

    sealed interface ViewState {
        data object Idle : ViewState

        data object Loading : ViewState

        data class Content(
            val walletId: Int? = null,
        ) : ViewState
    }

    sealed interface ViewEvent {
        data object FinishedAccountCreation : ViewEvent

        data class Error(
            val message: String,
        ) : ViewEvent
    }
}
