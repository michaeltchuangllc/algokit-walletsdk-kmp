package com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateViewModel

class LiquidAuthViewerViewModel(
    private val stateDelegate: StateDelegate<UiState>,
    private val eventDelegate: EventDelegate<ViewEvent>,
) : ViewModel(),
    StateViewModel<LiquidAuthViewerViewModel.UiState> by stateDelegate,
    EventViewModel<LiquidAuthViewerViewModel.ViewEvent> by eventDelegate {
    init {
        stateDelegate.setDefaultState(UiState())
    }

    fun onSettingsClicked() {
        stateDelegate.updateState {
            it.copy(
                showViewerSettingsSheet = true,
                showAnalyticsModal = false,
            )
        }
    }

    fun onViewerSettingsDismissed() {
        stateDelegate.updateState { it.copy(showViewerSettingsSheet = false) }
    }

    fun onAnalyticsClicked() {
        stateDelegate.updateState { it.copy(showAnalyticsModal = !it.showAnalyticsModal) }
    }

    fun onAnalyticsDismissed() {
        stateDelegate.updateState { it.copy(showAnalyticsModal = false) }
    }

    fun onPayoutFrequencyTabSelected(tabId: String) {
        stateDelegate.updateState { it.copy(selectedPayoutFrequencyTabId = tabId) }
    }

    fun onWillingToBeRelayerChanged(enabled: Boolean) {
        stateDelegate.updateState { it.copy(willingToBeRelayerEnabled = enabled) }
    }

    fun onMessageChanged(message: String) {
        stateDelegate.updateState { it.copy(message = message) }
    }

    fun onSendClicked() {
        val message = state.value.message.trim()
        if (message.isEmpty()) {
            eventDelegate.sendEvent(viewModelScope, ViewEvent.ShowError("Message cannot be empty"))
            return
        }
        eventDelegate.sendEvent(viewModelScope, ViewEvent.SendMessage(message))
        stateDelegate.updateState { it.copy(message = "") }
    }

    data class UiState(
        val showAnalyticsModal: Boolean = false,
        val showViewerSettingsSheet: Boolean = false,
        val selectedPayoutFrequencyTabId: String = PAYOUT_EVERY_256_BLOCKS_TAB_ID,
        val willingToBeRelayerEnabled: Boolean = false,
        val message: String = "",
        val realTimeRate: String = "0.42",
        val streamRevenue: String = "+1.402.15",
        val securedViaLabel: String = "Secured via Algorand Mainnet",
        val blockNumberLabel: String = "#38291041",
    )

    companion object {
        const val PAYOUT_EVERY_256_BLOCKS_TAB_ID = "every_256_blocks"
    }

    sealed interface ViewEvent {
        data class SendMessage(
            val message: String,
        ) : ViewEvent

        data class ShowError(
            val message: String,
        ) : ViewEvent
    }
}
