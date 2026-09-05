package com.michaeltchuang.walletsdk.ui.settings.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.michaeltchuang.walletsdk.core.foundation.utils.toShortenedAddress
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.model.IceConnectionType
import com.michaeltchuang.walletsdk.ui.liquidStream.components.ConnectedViewerInfo
import com.michaeltchuang.walletsdk.ui.liquidStream.components.rememberStandaloneCameraPreview
import com.michaeltchuang.walletsdk.ui.liquidStream.screens.LiquidStreamHostLiveScreenContent
import com.michaeltchuang.walletsdk.ui.liquidStream.utils.PAYOUT_BATCH_BLOCK_COUNT
import com.michaeltchuang.walletsdk.ui.liquidStream.utils.PAYOUT_EVERY_256_BLOCKS_TAB_ID
import com.michaeltchuang.walletsdk.ui.liquidStream.viewmodels.ChatUiMessage
import com.michaeltchuang.walletsdk.ui.liquidStream.viewmodels.LiquidStreamHostViewModel
import com.michaeltchuang.walletsdk.ui.settings.domain.DebugAddressHolder
import com.michaeltchuang.walletsdk.ui.settings.viewmodels.LiquidStreamHostDebugToolViewModel
import kotlinx.coroutines.delay
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.koin.compose.viewmodel.koinViewModel
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun LiquidStreamHostDebugToolScreen(
    viewModel: LiquidStreamHostViewModel = koinViewModel(),
    debugViewModel: LiquidStreamHostDebugToolViewModel = koinViewModel(),
    onSettingsClick: () -> Unit = {},
    onMinimise: () -> Unit = {},
    onWalletClick: () -> Unit = {},
    onCameraClick: (isEnabled: Boolean) -> Unit = {},
    onMicClick: (isMuted: Boolean) -> Unit = {},
    onRotateCamera: () -> Unit = {},
    onStatsClick: () -> Unit = {},
    onStatsModalVisibilityChanged: (Boolean) -> Unit = {},
    onSendClick: (String) -> Unit = {},
    blockChainLabel: String = "ALGORAND",
    balanceCurrencySymbol: String = "¦",
) {
    val cameraPreviewComp = rememberStandaloneCameraPreview()
    val uiState = viewModel.state.collectAsStateWithLifecycle().value
    val debugState = debugViewModel.state.collectAsStateWithLifecycle().value
    var statusMessage by remember { mutableStateOf<String?>(null) }

    DisposableEffect(debugViewModel) {
        onDispose {
            debugViewModel.closeAllSessions()
        }
    }

    LaunchedEffect(debugViewModel) {
        debugViewModel.viewEvent.collect { event ->
            when (event) {
                is LiquidStreamHostDebugToolViewModel.ViewEvent.ShowStatusMessage -> {
                    statusMessage = event.message
                }
                is LiquidStreamHostDebugToolViewModel.ViewEvent.ChatMessageGenerated -> {
                    viewModel.receivedChatMessage(event.message)
                }
            }
        }
    }

    val streamRevenueLabel =
        if (debugState.totalRevenueMicroUsdc > 0) {
            "+${(debugState.totalRevenueMicroUsdc / 1_000_000.0 * 100).toLong() / 100.0}"
        } else {
            "0.00"
        }
    val blockNumberLabelLabel = debugState.liveBlockNumber?.let { "#$it" } ?: "-"
    val securedViaLabel = debugState.liveNetworkLabel

    LaunchedEffect(Unit) {
        viewModel.viewEvent.collect { event ->
            when (event) {
                is LiquidStreamHostViewModel.ViewEvent.SendMessage -> onSendClick(event.message)
                is LiquidStreamHostViewModel.ViewEvent.ShowError -> Unit
                is LiquidStreamHostViewModel.ViewEvent.ToggleMic -> onMicClick(event.isMuted)
                is LiquidStreamHostViewModel.ViewEvent.ToggleCamera -> onCameraClick(event.isEnabled)
                is LiquidStreamHostViewModel.ViewEvent.StreamCostChanged -> {
                    debugViewModel.setStreamCost(event.costMicroUsdc)
                }
                is LiquidStreamHostViewModel.ViewEvent.PayoutFrequencyChanged -> {
                    val blocks =
                        if (event.tabId == PAYOUT_EVERY_256_BLOCKS_TAB_ID) {
                            PAYOUT_BATCH_BLOCK_COUNT
                        } else {
                            1
                        }
                    debugViewModel.setPayoutFrequency(blocks)
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LiquidStreamHostLiveScreenContent(
            cameraPreview = cameraPreviewComp,
            creatorUsername = debugState.creatorNfdName ?: DebugAddressHolder.creatorAddress.toShortenedAddress(),
            creatorAvatarUrl = debugState.creatorNfdAvatarUrl,
            numbersOfViewer = debugState.viewers.size.toString(),
            onSettingsClick = {
                viewModel.onSettingsClicked()
                onStatsModalVisibilityChanged(false)
                onSettingsClick()
            },
            onMinimise = onMinimise,
            onWalletClick = onWalletClick,
            onCameraClick = viewModel::onCameraClicked,
            onMicClick = viewModel::onMicClicked,
            onRotateCamera = onRotateCamera,
            onStatsClick = {
                val isStatsVisible = !uiState.isStatsModalVisible
                viewModel.onStatsClicked()
                onStatsModalVisibilityChanged(isStatsVisible)
                onStatsClick()
            },
            onSendClickInternal = { viewModel.onSendClicked() },
            viewers = debugState.viewers,
            blockChainLabel = blockChainLabel,
            balanceCurrencySymbol = balanceCurrencySymbol,
            streamRevenue = streamRevenueLabel,
            blockNumberLabel = blockNumberLabelLabel,
            securedViaLabel = securedViaLabel,
            uiState = uiState,
            onTextChanged = viewModel::onMessageChanged,
            onStatsDismissed = {
                viewModel.onStatsDismissed()
                onStatsModalVisibilityChanged(false)
            },
            onStreamCostTabSelected = viewModel::onStreamCostTabSelected,
            onPayoutFrequencyTabSelected = viewModel::onPayoutFrequencyTabSelected,
            onSubsidizeViewerFeesChanged = viewModel::onSubsidizeViewerFeesChanged,
            onSettingsDismissed = viewModel::onSettingsDismissed,
        )

        LaunchedEffect(uiState.selectedStreamCostTabId) {
            val isPaid = uiState.selectedStreamCostTabId == LiquidStreamHostViewModel.STREAM_COST_PAID_TAB_ID
            debugViewModel.setIsPaidStreaming(isPaid)
        }

        // Floating Debug Status Info
        Column(
            modifier =
                Modifier
                    .padding(16.dp)
                    .width(200.dp)
                    .align(Alignment.TopStart)
                    .padding(top = 100.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            statusMessage?.let { msg ->
                androidx.compose.material3.Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        androidx.compose.material3.CardDefaults.cardColors(
                            containerColor = if (msg.startsWith("✅")) Color(0xFFE8F5E9) else Color(0xFFFFEBEE),
                        ),
                ) {
                    Text(
                        text = msg,
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (msg.startsWith("✅")) Color(0xFF2E7D32) else Color(0xFFC62828),
                    )
                }

                LaunchedEffect(msg) {
                    delay(5000.milliseconds)
                    statusMessage = null
                }
            }

            if (debugState.isLoading) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("Auto-processing on-chain...", style = MaterialTheme.typography.labelSmall, color = Color.White)
                }
            }
        }
    }
}

@Preview
@Composable
fun LiquidStreamHostDebugScreenPreview() {
    AlgoKitTheme {
        var uiState by remember {
            mutableStateOf(
                LiquidStreamHostViewModel.UiState(
                    chatMessages =
                        listOf(
                            ChatUiMessage(
                                sender = "michaeltchuang.algo",
                                text = "Hello from the preview!",
                                timestamp = 0L,
                            ),
                            ChatUiMessage(
                                sender = "viewer.algo",
                                text = "This is a preview message",
                                timestamp = 0L,
                            ),
                            ChatUiMessage(
                                sender = "gift.algo",
                                text = "Supporting the stream!",
                                timestamp = 0L,
                                amount = "10.0",
                                asset = "USDC",
                            ),
                        ),
                ),
            )
        }
        LiquidStreamHostLiveScreenContent(
            cameraPreview = null,
            creatorUsername = "michaeltchuang.algo",
            numbersOfViewer = "1",
            onSettingsClick = { uiState = uiState.copy(isSettingsModalVisible = true, isStatsModalVisible = false) },
            onMinimise = {},
            onWalletClick = {},
            onCameraClick = { uiState = uiState.copy(isCameraEnabled = !uiState.isCameraEnabled) },
            onMicClick = { uiState = uiState.copy(isMicMuted = !uiState.isMicMuted) },
            onRotateCamera = {},
            onStatsClick = { uiState = uiState.copy(isStatsModalVisible = !uiState.isStatsModalVisible) },
            onSendClickInternal = { uiState = uiState.copy(message = "") },
            viewers =
                listOf(
                    ConnectedViewerInfo(
                        sessionId = "session-preview-id",
                        remainingBalanceUSDC = 12.0,
                        progressBalanceUSDC = 11.9,
                        progressCapacityUSDC = 12.0,
                        revenueCapacityUSDC = 12.0,
                        connectionType = IceConnectionType.UNKNOWN,
                        currentBlockNumber = 38291041L,
                        networkLabel = "TESTNET",
                        originUrl = "https://example.app",
                    ),
                ),
            blockChainLabel = "ALGORAND",
            balanceCurrencySymbol = "A",
            streamRevenue = "+0.00",
            blockNumberLabel = "#38291041",
            uiState = uiState,
            onTextChanged = { message -> uiState = uiState.copy(message = message) },
            onStatsDismissed = { uiState = uiState.copy(isStatsModalVisible = false) },
            onStreamCostTabSelected = { tabId -> uiState = uiState.copy(selectedStreamCostTabId = tabId) },
            onPayoutFrequencyTabSelected = { tabId -> uiState = uiState.copy(selectedPayoutFrequencyTabId = tabId) },
            onSubsidizeViewerFeesChanged = { enabled -> uiState = uiState.copy(subsidizeViewerFeesEnabled = enabled) },
            onSettingsDismissed = { uiState = uiState.copy(isSettingsModalVisible = false) },
        )
    }
}
