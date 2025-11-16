package com.michaeltchuang.walletsdk.ui.onboarding.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.account.domain.usecase.custom.GetAccountCustomName
import com.michaeltchuang.walletsdk.core.account.domain.usecase.custom.SetAccountCustomName
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateViewModel
import com.michaeltchuang.walletsdk.core.foundation.utils.toShortenedAddress
import kotlinx.coroutines.launch

class AddressNamingViewModel(
    private val setAccountCustomName: SetAccountCustomName,
    private val getAccountCustomName: GetAccountCustomName,
    private val stateDelegate: StateDelegate<ViewState>,
    private val eventDelegate: EventDelegate<ViewEvent>,
) : ViewModel(),
    StateViewModel<AddressNamingViewModel.ViewState> by stateDelegate,
    EventViewModel<AddressNamingViewModel.ViewEvent> by eventDelegate {
    init {
        stateDelegate.setDefaultState(ViewState.Idle)
    }

    fun fetchAccountDetails(address: String) {
        viewModelScope.launch {
            val currentName = getAccountCustomName(address) ?: address.toShortenedAddress()
            stateDelegate.updateState { ViewState.Content(address, currentName) }
        }
    }

    fun saveCustomName(name: String) {
        stateDelegate.onState<ViewState.Content> { contentState ->
            viewModelScope.launch {
                setAccountCustomName(contentState.address, name)
                eventDelegate.sendEvent(ViewEvent.FinishedAccountCreation)
            }
        }
    }

    sealed interface ViewState {
        data object Idle : ViewState

        data object Loading : ViewState

        data class Content(
            val address: String,
            val currentName: String,
        ) : ViewState
    }

    sealed interface ViewEvent {
        data object FinishedAccountCreation : ViewEvent

        data class Error(
            val message: String,
        ) : ViewEvent
    }
}
