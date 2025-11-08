package com.michaeltchuang.walletsdk.ui.accountdetails.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateViewModel
import com.michaeltchuang.walletsdk.core.foundation.utils.toShortenedAddress
import kotlinx.coroutines.launch

class QRCodeViewModel(
    private val stateDelegate: StateDelegate<ViewState>,
    private val eventDelegate: EventDelegate<ViewEvent>,
) : ViewModel(),
    StateViewModel<QRCodeViewModel.ViewState> by stateDelegate,
    EventViewModel<QRCodeViewModel.ViewEvent> by eventDelegate {
    init {
        stateDelegate.setDefaultState(ViewState.Idle)
    }

    fun setAddress(address: String) {
        viewModelScope.launch {
            try {
                if (address.isBlank()) {
                    stateDelegate.updateState { ViewState.Error("Invalid address") }
                    return@launch
                }

                stateDelegate.updateState {
                    ViewState.Content(
                        address = address,
                        displayAddress = address.toShortenedAddress(),
                    )
                }
            } catch (e: Exception) {
                stateDelegate.updateState { ViewState.Error("Failed to load address: ${e.message}") }
            }
        }
    }

    fun copyAddress(copiedMessage: String) {
        viewModelScope.launch {
            stateDelegate.onState<ViewState.Content> { currentState ->
                eventDelegate.sendEvent(viewModelScope, ViewEvent.AddressCopied(copiedMessage))
            }

            if (state.value !is ViewState.Content) {
                eventDelegate.sendEvent(viewModelScope, ViewEvent.Error("No address to copy"))
            }
        }
    }

    fun shareAddress(address: String) {
        viewModelScope.launch {
            try {
                val shareMessage = buildShareMessage(address)
                eventDelegate.sendEvent(
                    viewModelScope,
                    ViewEvent.AddressShared(address, shareMessage),
                )
            } catch (e: Exception) {
                eventDelegate.sendEvent(
                    viewModelScope,
                    ViewEvent.Error("Failed to share address: ${e.message}"),
                )
            }
        }
    }

    private fun buildShareMessage(address: String): String = "My Algorand wallet address: $address"

    sealed interface ViewState {
        data object Idle : ViewState

        data object Loading : ViewState

        data class Content(
            val address: String,
            val displayAddress: String,
        ) : ViewState

        data class Error(
            val message: String,
        ) : ViewState
    }

    sealed interface ViewEvent {
        data class AddressCopied(
            val message: String,
        ) : ViewEvent

        data class AddressShared(
            val address: String,
            val message: String,
        ) : ViewEvent

        data class Error(
            val message: String,
        ) : ViewEvent
    }
}
