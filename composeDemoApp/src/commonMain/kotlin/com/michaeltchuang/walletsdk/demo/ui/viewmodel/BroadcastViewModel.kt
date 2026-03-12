package com.michaeltchuang.walletsdk.demo.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateViewModel
import kotlinx.coroutines.launch

class BroadcastViewModel(
    private val stateDelegate: StateDelegate<BroadcastState>,
    private val eventDelegate: EventDelegate<BroadcastEvent>,
) : ViewModel(),
    StateViewModel<BroadcastViewModel.BroadcastState> by stateDelegate,
    EventViewModel<BroadcastViewModel.BroadcastEvent> by eventDelegate {

    init {
        stateDelegate.setDefaultState(BroadcastState.Idle)
    }

    fun generateQRCode(data: String) {
        stateDelegate.updateState { BroadcastState.Loading }
        viewModelScope.launch {
            try {
                if (data.isBlank()) {
                    stateDelegate.updateState { BroadcastState.Error("No data provided for QR code") }
                    return@launch
                }

                stateDelegate.updateState {
                    BroadcastState.Content(
                        qrData = data,
                    )
                }
            } catch (e: Exception) {
                stateDelegate.updateState { BroadcastState.Error(e.message ?: "Failed to generate QR code") }
                eventDelegate.sendEvent(
                    BroadcastEvent.ShowError(
                        e.message ?: "Failed to generate QR code.",
                    ),
                )
            }
        }
    }

    fun refreshQRCode() {
        viewModelScope.launch {
            stateDelegate.onState<BroadcastState.Content> { currentState ->
                eventDelegate.sendEvent(BroadcastEvent.QRCodeRefreshed)
                // Re-generate with same data to trigger any refresh logic
                generateQRCode(currentState.qrData)
            }
        }
    }

    sealed interface BroadcastState {
        data object Idle : BroadcastState

        data object Loading : BroadcastState

        data class Content(
            val qrData: String,
        ) : BroadcastState

        data class Error(
            val message: String,
        ) : BroadcastState
    }

    sealed interface BroadcastEvent {
        data class ShowError(
            val message: String,
        ) : BroadcastEvent

        data object QRCodeRefreshed : BroadcastEvent
    }
}
