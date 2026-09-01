package com.michaeltchuang.walletsdk.ui.liquidStream.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateViewModel
import com.michaeltchuang.walletsdk.core.foundation.utils.LiquidStreamConstants
import com.michaeltchuang.walletsdk.core.foundation.utils.toShortenedAddress
import com.michaeltchuang.walletsdk.core.network.usecase.GetNfdProfileForAddress
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ChatMessage
import com.michaeltchuang.walletsdk.ui.liquidStream.components.ConnectedViewerInfo
import com.michaeltchuang.walletsdk.ui.liquidStream.utils.PAYOUT_EVERY_BLOCK_TAB_ID
import com.michaeltchuang.walletsdk.ui.liquidStream.utils.formatRevenueLabel
import com.michaeltchuang.walletsdk.ui.liquidStream.utils.formatTwoDecimals
import io.github.aakira.napier.Napier
import kotlinx.coroutines.launch
import kotlin.time.Clock

class LiquidStreamHostViewModel(
    private val getNfdProfileForAddress: GetNfdProfileForAddress,
    private val stateDelegate: StateDelegate<UiState>,
    private val eventDelegate: EventDelegate<ViewEvent>,
) : ViewModel(),
    StateViewModel<LiquidStreamHostViewModel.UiState> by stateDelegate,
    EventViewModel<LiquidStreamHostViewModel.ViewEvent> by eventDelegate {
    init {
        stateDelegate.setDefaultState(UiState())
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
                Napier.e("Failed to fetch creator NFD profile for $address", e, tag = "LiquidStreamHostVM")
            }
        }
    }

    fun loadViewerNfdProfile(address: String) {
        if (address.isBlank() || state.value.viewerNfdNames.containsKey(address)) return
        viewModelScope.launch {
            try {
                val nfdProfile = getNfdProfileForAddress(address)
                if (nfdProfile?.name != null) {
                    stateDelegate.updateState { currentState ->
                        val updatedNfdNames = currentState.viewerNfdNames + (address to nfdProfile.name)
                        val updatedViewers =
                            currentState.viewers.map { viewer ->
                                val rawAddr = viewer.viewerAddress.orEmpty()
                                if (rawAddr == address || rawAddr == address.toShortenedAddress()) {
                                    viewer.copy(viewerAddress = nfdProfile.name)
                                } else {
                                    viewer
                                }
                            }
                        currentState.copy(
                            viewerNfdNames = updatedNfdNames,
                            viewers = updatedViewers,
                        )
                    }
                }
            } catch (e: Exception) {
                Napier.e("Failed to fetch viewer NFD profile for $address", e, tag = "LiquidStreamHostVM")
            }
        }
    }

    fun onMessageChanged(message: String) {
        stateDelegate.updateState { it.copy(message = message) }
    }

    fun onSendClicked() {
        val messageText = state.value.message.trim()
        if (messageText.isEmpty()) {
            eventDelegate.sendEvent(viewModelScope, ViewEvent.ShowError("Message cannot be empty"))
            return
        }

        // Add locally immediately
        receivedChatMessage(
            ChatMessage(
                sender = "You",
                text = messageText,
                timestamp = Clock.System.now().toEpochMilliseconds(),
            ),
        )

        eventDelegate.sendEvent(viewModelScope, ViewEvent.SendMessage(messageText))
        stateDelegate.updateState { it.copy(message = "") }
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
        stateDelegate.updateState {
            val newState = it.copy(selectedStreamCostTabId = tabId)
            calculateMetrics(newState)
        }
        val cost =
            if (tabId == STREAM_COST_PAID_TAB_ID) {
                LiquidStreamConstants.COST_PER_BLOCK_MICRO_USDC
            } else {
                0L
            }
        eventDelegate.sendEvent(viewModelScope, ViewEvent.StreamCostChanged(cost))
    }

    fun onPayoutFrequencyTabSelected(tabId: String) {
        stateDelegate.updateState { it.copy(selectedPayoutFrequencyTabId = tabId) }
        eventDelegate.sendEvent(viewModelScope, ViewEvent.PayoutFrequencyChanged(tabId))
    }

    fun onSubsidizeViewerFeesChanged(enabled: Boolean) {
        stateDelegate.updateState { it.copy(subsidizeViewerFeesEnabled = enabled) }
    }

    fun updateMetrics(
        currentBlockNumber: Long? = null,
        blockChainLabel: String? = null,
        networkLabel: String? = null,
        numbersOfViewer: String? = null,
        viewers: List<ConnectedViewerInfo>? = null,
        startRound: Long? = null,
    ) {
        stateDelegate.updateState { currentState ->
            val block = currentBlockNumber ?: currentState.currentBlockNumber
            val chain = blockChainLabel ?: currentState.blockChainLabel
            val net = networkLabel ?: currentState.networkLabel
            val viewerCount = numbersOfViewer ?: currentState.numbersOfViewer
            val rawViewers =
                if (startRound != null && viewers == null) {
                    currentState.viewers.map { it.copy(startRound = startRound) }
                } else {
                    viewers ?: currentState.viewers
                }

            val currentViewers =
                rawViewers.map { viewer ->
                    val rawAddress = viewer.viewerAddress
                    if (rawAddress != null && currentState.viewerNfdNames.containsKey(rawAddress)) {
                        viewer.copy(viewerAddress = currentState.viewerNfdNames[rawAddress])
                    } else {
                        viewer
                    }
                }

            val baseState =
                currentState.copy(
                    currentBlockNumber = block,
                    blockChainLabel = chain,
                    networkLabel = net,
                    numbersOfViewer = viewerCount,
                    viewers = currentViewers,
                )

            calculateMetrics(baseState)
        }
    }

    private fun calculateMetrics(currentState: UiState): UiState {
        val calculatedRevenue = currentState.viewers.mapNotNull { it.lastSettledUSDC }.sum()
        val startRound = currentState.viewers.mapNotNull { it.startRound }.firstOrNull() ?: 0L
        val currentBlock = currentState.currentBlockNumber ?: 0L

        val isPaid = currentState.selectedStreamCostTabId == STREAM_COST_PAID_TAB_ID
        val rate =
            if (isPaid) {
                if (currentBlock > startRound && startRound > 0) {
                    formatTwoDecimals(calculatedRevenue / (currentBlock - startRound))
                } else {
                    formatTwoDecimals(LiquidStreamConstants.COST_PER_BLOCK_MICRO_USDC / 1_000_000.0)
                }
            } else {
                "0.00"
            }
        val revenueLabel =
            if (isPaid) {
                formatRevenueLabel(calculatedRevenue)
            } else {
                "0.00"
            }

        return currentState.copy(
            totalRevenue = calculatedRevenue,
            blockNumberLabel = currentState.currentBlockNumber?.let { "#$it" } ?: "-",
            securedViaLabel = "Secured via ${currentState.blockChainLabel} ${currentState.networkLabel}",
            realTimeRate = rate,
            streamRevenue = revenueLabel,
        )
    }

    fun onMicClicked() {
        val newMutedState = !state.value.isMicMuted
        stateDelegate.updateState { it.copy(isMicMuted = newMutedState) }
        eventDelegate.sendEvent(viewModelScope, ViewEvent.ToggleMic(isMuted = newMutedState))
    }

    fun onCameraClicked() {
        val newEnabledState = !state.value.isCameraEnabled
        stateDelegate.updateState { it.copy(isCameraEnabled = newEnabledState) }
        eventDelegate.sendEvent(viewModelScope, ViewEvent.ToggleCamera(isEnabled = newEnabledState))
    }

    data class UiState(
        val message: String = "",
        val isStatsModalVisible: Boolean = false,
        val isSettingsModalVisible: Boolean = false,
        val selectedStreamCostTabId: String = STREAM_COST_PAID_TAB_ID,
        val selectedPayoutFrequencyTabId: String = PAYOUT_EVERY_BLOCK_TAB_ID,
        val subsidizeViewerFeesEnabled: Boolean = false,
        val realTimeRate: String = "0.00",
        val streamRevenue: String = "0.00",
        val securedViaLabel: String = "-",
        val blockNumberLabel: String = "-",
        val isMicMuted: Boolean = false,
        val isCameraEnabled: Boolean = true,
        val chatMessages: List<ChatUiMessage> = emptyList(),
        val currentBlockNumber: Long? = null,
        val blockChainLabel: String = "",
        val networkLabel: String = "",
        val numbersOfViewer: String = "0",
        val totalRevenue: Double = 0.0,
        val viewers: List<ConnectedViewerInfo> = emptyList(),
        val creatorNfdName: String? = null,
        val creatorNfdAvatarUrl: String? = null,
        val viewerNfdNames: Map<String, String> = emptyMap(),
    )

    companion object {
        const val STREAM_COST_PAID_TAB_ID = "paid"
    }

    sealed interface ViewEvent {
        data class SendMessage(
            val message: String,
        ) : ViewEvent

        data class ShowError(
            val message: String,
        ) : ViewEvent

        data class ToggleMic(
            val isMuted: Boolean,
        ) : ViewEvent

        data class ToggleCamera(
            val isEnabled: Boolean,
        ) : ViewEvent

        data class StreamCostChanged(
            val costMicroUsdc: Long,
        ) : ViewEvent

        data class PayoutFrequencyChanged(
            val tabId: String,
        ) : ViewEvent
    }
}
