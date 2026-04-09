package com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateViewModel

class LiquidStreamHostViewModel(
    private val stateDelegate: StateDelegate<UiState>,
    private val eventDelegate: EventDelegate<ViewEvent>,
) : ViewModel(),
    StateViewModel<LiquidStreamHostViewModel.UiState> by stateDelegate,
    EventViewModel<LiquidStreamHostViewModel.ViewEvent> by eventDelegate {
    init {
        stateDelegate.setDefaultState(UiState())
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

    fun onSettingsClicked() {
        stateDelegate.updateState {
            it.copy(
                isSettingsModalVisible = true,
                isStatsModalVisible = false,
            )
        }
    }

    fun onSettingsDismissed() {
        stateDelegate.updateState { it.copy(isSettingsModalVisible = false) }
    }

    fun onStatsClicked() {
        stateDelegate.updateState { it.copy(isStatsModalVisible = !it.isStatsModalVisible) }
    }

    fun onStatsDismissed() {
        stateDelegate.updateState { it.copy(isStatsModalVisible = false) }
    }

    fun onStreamCostTabSelected(tabId: String) {
        stateDelegate.updateState { it.copy(selectedStreamCostTabId = tabId) }
    }

    fun onPayoutFrequencyTabSelected(tabId: String) {
        stateDelegate.updateState { it.copy(selectedPayoutFrequencyTabId = tabId) }
    }

    fun onSubsidizeViewerFeesChanged(enabled: Boolean) {
        stateDelegate.updateState { it.copy(subsidizeViewerFeesEnabled = enabled) }
    }

    data class UiState(
        val message: String = "",
        val isStatsModalVisible: Boolean = false,
        val isSettingsModalVisible: Boolean = false,
        val selectedStreamCostTabId: String = STREAM_COST_PAID_TAB_ID,
        val selectedPayoutFrequencyTabId: String = PAYOUT_EVERY_256_BLOCKS_TAB_ID,
        val subsidizeViewerFeesEnabled: Boolean = false,
        val realTimeRate: String = "0.42",
        val streamRevenue: String = "+1.402.15",
        val securedViaLabel: String = "Secured via Algorand Mainnet",
        val blockNumberLabel: String = "#38291041",
    )

    companion object {
        const val STREAM_COST_PAID_TAB_ID = "paid"
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
