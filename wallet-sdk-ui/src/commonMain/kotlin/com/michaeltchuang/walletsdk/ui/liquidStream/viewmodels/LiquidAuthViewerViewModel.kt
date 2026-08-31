package com.michaeltchuang.walletsdk.ui.liquidStream.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateViewModel
import com.michaeltchuang.walletsdk.core.network.domain.usecase.GetCurrentNetworkUseCase
import com.michaeltchuang.walletsdk.core.network.model.AlgorandNetwork
import com.michaeltchuang.walletsdk.core.network.usecase.GetNfdProfileForAddress
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ChatMessage
import com.michaeltchuang.walletsdk.ui.liquidStream.utils.PAYOUT_EVERY_BLOCK_TAB_ID
import io.github.aakira.napier.Napier
import kotlinx.coroutines.launch

class LiquidAuthViewerViewModel(
    private val getNfdProfileForAddress: GetNfdProfileForAddress,
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

    fun loadCreatorNfdProfile(address: String) {
        if (address.isBlank()) return
        viewModelScope.launch {
            try {
                val nfdProfile = getNfdProfileForAddress(address)
                stateDelegate.updateState {
                    it.copy(
                        creatorNfdName = nfdProfile?.name,
                        creatorNfdAvatarUrl = nfdProfile?.avatarUrl,
                    )
                }
            } catch (e: Exception) {
                Napier.e("Failed to fetch creator NFD profile for $address", e, tag = "LiquidAuthViewerVM")
            }
        }
    }

    fun loadViewerNfdProfile(address: String) {
        if (address.isBlank() || address == "-") return
        viewModelScope.launch {
            try {
                val nfdProfile = getNfdProfileForAddress(address)
                if (nfdProfile?.name != null) {
                    stateDelegate.updateState {
                        it.copy(viewerNfdName = nfdProfile.name)
                    }
                }
            } catch (e: Exception) {
                Napier.e("Failed to fetch viewer NFD profile for $address", e, tag = "LiquidAuthViewerVM")
            }
        }
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

        eventDelegate.sendEvent(viewModelScope, ViewEvent.SendMessage(messageText))
        stateDelegate.updateState { it.copy(message = "", giftAmountTag = ZERO_GIFT_AMOUNT) }
    }

    fun receivedChatMessage(message: ChatMessage) {
        stateDelegate.updateState {
            it.copy(
                chatMessages =
                    it.chatMessages +
                        ChatUiMessage(
                            sender = message.sender,
                            text = message.text,
                            timestamp = message.timestamp,
                            amount = message.amount,
                            asset = message.asset,
                        ),
            )
        }
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
        val selectedPayoutFrequencyTabId: String = PAYOUT_EVERY_BLOCK_TAB_ID,
        val willingToBeRelayerEnabled: Boolean = false,
        val message: String = "",
        val realTimeRate: String = "0.42",
        val streamRevenue: String = "+1.402.15",
        val blockNumberLabel: String = "#--------",
        val network: AlgorandNetwork = AlgorandNetwork.TESTNET,
        val chatMessages: List<ChatUiMessage> = emptyList(),
        val creatorNfdName: String? = null,
        val creatorNfdAvatarUrl: String? = null,
        val viewerNfdName: String? = null,
    ) {
        val networkLabel: String
            get() = network.name
    }

    companion object {
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
