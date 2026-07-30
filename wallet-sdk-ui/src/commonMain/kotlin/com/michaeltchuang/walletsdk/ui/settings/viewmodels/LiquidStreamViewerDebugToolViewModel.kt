package com.michaeltchuang.walletsdk.ui.settings.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateViewModel
import com.michaeltchuang.walletsdk.core.network.domain.usecase.GetCurrentNetworkUseCase
import com.michaeltchuang.walletsdk.core.network.model.AlgorandNetwork
import com.michaeltchuang.walletsdk.ui.liquidStream.viewmodels.ChatUiMessage
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.time.Clock

class LiquidStreamViewerDebugToolViewModel(
    private val stateDelegate: StateDelegate<UiState>,
    private val eventDelegate: EventDelegate<ViewEvent>,
    private val getCurrentNetworkUseCase: GetCurrentNetworkUseCase,
) : ViewModel(),
    StateViewModel<LiquidStreamViewerDebugToolViewModel.UiState> by stateDelegate,
    EventViewModel<LiquidStreamViewerDebugToolViewModel.ViewEvent> by eventDelegate {

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
        val messageText = state.value.message.trim()
        if (messageText.isEmpty()) {
            stateDelegate.updateState { it.copy(giftAmountTag = ZERO_GIFT_AMOUNT) }
            eventDelegate.sendEvent(viewModelScope, ViewEvent.ShowError("Message cannot be empty"))
            return
        }

        // For debug tool, we just add the message locally
        val newMessage = ChatUiMessage(
            sender = "Viewer",
            text = messageText,
            timestamp = Clock.System.now().toEpochMilliseconds(),
            amount = if (state.value.giftAmountTag.toDoubleOrNull() ?: 0.0 > 0.0) state.value.giftAmountTag else null,
            asset = if (state.value.giftAmountTag.toDoubleOrNull() ?: 0.0 > 0.0) "USDC" else null
        )

        stateDelegate.updateState {
            it.copy(
                chatMessages = it.chatMessages + newMessage,
                message = "",
                giftAmountTag = ZERO_GIFT_AMOUNT
            )
        }
    }

    private fun observeNetwork() {
        getCurrentNetworkUseCase()
            .onEach { network ->
                stateDelegate.updateState { it.copy(network = network) }
            }
            .launchIn(viewModelScope)
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
        val chatMessages: List<ChatUiMessage> = emptyList(),
    ) {
        val networkLabel: String
            get() = network.name
    }

    companion object {
        const val PAYOUT_EVERY_256_BLOCKS_TAB_ID = "every_256_blocks"
        private const val ZERO_GIFT_AMOUNT = "0.00"
    }

    sealed interface ViewEvent {
        data class ShowError(
            val message: String,
        ) : ViewEvent
    }
}
