package com.michaeltchuang.walletsdk.ui.liquidStream.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateViewModel
import com.michaeltchuang.walletsdk.core.network.domain.usecase.GetCurrentNetworkUseCase
import com.michaeltchuang.walletsdk.core.network.model.AlgorandNetwork
import kotlinx.coroutines.launch

class LiquidAuthViewerViewModel(
    private val stateDelegate: StateDelegate<UiState>,
    private val eventDelegate: EventDelegate<ViewEvent>,
    private val getCurrentNetworkUseCase: GetCurrentNetworkUseCase,
) : ViewModel(),
    StateViewModel<LiquidAuthViewerViewModel.UiState> by stateDelegate,
    EventViewModel<LiquidAuthViewerViewModel.ViewEvent> by eventDelegate {
    init {
        stateDelegate.setDefaultState(UiState())
        observeNetwork()
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

    fun onTopUpClicked() {
        stateDelegate.updateState { it.copy(showTopUpSheet = true) }
    }

    fun onTopUpDismissed() {
        stateDelegate.updateState { it.copy(showTopUpSheet = false) }
    }

    fun onGiftSupportClicked() {
        stateDelegate.updateState { it.copy(showGiftSupportSheet = true) }
    }

    fun onGiftSupportDismissed() {
        stateDelegate.updateState { it.copy(showGiftSupportSheet = false) }
    }

    fun onGiftAmountSelected(amount: String) {
        stateDelegate.updateState { it.copy(giftAmountTag = amount) }
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
            stateDelegate.updateState { it.copy(giftAmountTag = ZERO_GIFT_AMOUNT) }
            eventDelegate.sendEvent(viewModelScope, ViewEvent.ShowError("Message cannot be empty"))
            return
        }
        eventDelegate.sendEvent(viewModelScope, ViewEvent.SendMessage(message))
        stateDelegate.updateState { it.copy(message = "", giftAmountTag = ZERO_GIFT_AMOUNT) }
    }

    private fun observeNetwork() {
        viewModelScope.launch {
            getCurrentNetworkUseCase().collect { network ->
                stateDelegate.updateState { it.copy(network = network) }
            }
        }
    }

    data class UiState(
        val showAnalyticsModal: Boolean = false,
        val showViewerSettingsSheet: Boolean = false,
        val showTopUpSheet: Boolean = false,
        val showGiftSupportSheet: Boolean = false,
        val giftAmountTag: String = "0.88",
        val selectedPayoutFrequencyTabId: String = PAYOUT_EVERY_256_BLOCKS_TAB_ID,
        val willingToBeRelayerEnabled: Boolean = false,
        val message: String = "",
        val realTimeRate: String = "0.42",
        val streamRevenue: String = "+1.402.15",
        val blockNumberLabel: String = "#--------",
        val network: AlgorandNetwork = AlgorandNetwork.TESTNET,
    ) {
        val networkLabel: String
            get() = network.name
    }

    companion object {
        const val PAYOUT_EVERY_256_BLOCKS_TAB_ID = "every_256_blocks"
        private const val ZERO_GIFT_AMOUNT = "0.00"
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
