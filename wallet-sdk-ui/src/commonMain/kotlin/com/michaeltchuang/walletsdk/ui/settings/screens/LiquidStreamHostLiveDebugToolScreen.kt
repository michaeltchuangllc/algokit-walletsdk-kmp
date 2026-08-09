package com.michaeltchuang.walletsdk.ui.settings.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.michaeltchuang.walletsdk.core.foundation.utils.toShortenedAddress
import com.michaeltchuang.walletsdk.core.network.domain.usecase.GetCurrentNetworkUseCase
import com.michaeltchuang.walletsdk.core.network.usecase.GetCurrentBlockUseCase
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetSessionVaultContextUseCase
import com.michaeltchuang.walletsdk.core.railmpp.utils.MppPayments
import com.michaeltchuang.walletsdk.core.railmpp.smartcontract.EscrowSessionVaultManagerClient
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ChatMessage
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.model.IceConnectionType
import com.michaeltchuang.walletsdk.ui.liquidStream.components.ConnectedViewerInfo
import com.michaeltchuang.walletsdk.ui.liquidStream.components.rememberStandaloneCameraPreview
import com.michaeltchuang.walletsdk.ui.liquidStream.screens.LiquidStreamHostLiveScreenContent
import com.michaeltchuang.walletsdk.ui.liquidStream.viewmodels.ChatUiMessage
import com.michaeltchuang.walletsdk.ui.liquidStream.viewmodels.LiquidStreamHostViewModel
import com.michaeltchuang.walletsdk.ui.settings.domain.DebugAddressHolder
import com.michaeltchuang.walletsdk.ui.settings.viewmodels.LiquidStreamHostDebugToolViewModel
import com.michaeltchuang.walletsdk.utils.DataResource
import io.github.aakira.napier.Napier
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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
    sessionId: String? = "debug-session-id",
    progressBalanceUsdc: Double? = 50.0,
    remainingBalanceUsdc: Double? = 25.0,
    connectionType: IceConnectionType = IceConnectionType.STUN,
    currentBlockNumber: Long? = 12345678L,
    blockChainLabel: String = "ALGORAND",
    networkLabel: String = "TESTNET",
    balanceCurrencySymbol: String = "¦",
    originUrl: String = "https://debug.app",
) {
    val cameraPreviewComp = rememberStandaloneCameraPreview()
    val uiState = viewModel.state.collectAsStateWithLifecycle().value
    val debugState = debugViewModel.state.collectAsStateWithLifecycle().value
    var progressCapacityUsdc by remember(sessionId) { mutableDoubleStateOf(0.0) }

    LaunchedEffect(
        DebugAddressHolder.viewerAddress,
        DebugAddressHolder.viewerAddress2,
        DebugAddressHolder.viewerAddress3,
    ) {
        debugViewModel.refreshViewerBalances()
    }

    LaunchedEffect(remainingBalanceUsdc) {
        val balance = remainingBalanceUsdc ?: 0.0
        if (balance > progressCapacityUsdc) {
            progressCapacityUsdc = balance
        }
    }

    LaunchedEffect(Unit) {
        Napier.d("Viewer 1 Address: ${DebugAddressHolder.viewerAddress}", tag = "LiquidStreamHostDebug")
        Napier.d("Viewer 2 Address: ${DebugAddressHolder.viewerAddress2}", tag = "LiquidStreamHostDebug")
        Napier.d("Viewer 3 Address: ${DebugAddressHolder.viewerAddress3}", tag = "LiquidStreamHostDebug")
        Napier.d("Creator Address: ${DebugAddressHolder.creatorAddress}", tag = "LiquidStreamHostDebug")

        // Auto-generated message pump for testing
        val randomNames = listOf("alice.algo", "bob.algo", "charlie.algo", "dave.algo", "eve.algo")
        val randomTexts =
            listOf(
                "Hello world!",
                "This stream is awesome!",
                "Keep it up!",
                "Love the content!",
                "Wow, very informative.",
                "Testing 1 2 3",
                "Liquid Stream is the future!",
            )

        while (true) {
            delay(2000.milliseconds)
            val isGift = Random.nextBoolean()
            val sender = randomNames.random()
            val text = randomTexts.random()
            val timestamp = Clock.System.now().toEpochMilliseconds()

            if (isGift) {
                viewModel.receivedChatMessage(
                    ChatMessage(
                        sender = sender,
                        text = "Gifting support: $text",
                        timestamp = timestamp,
                        amount = Random.nextInt(1, 100).toString(),
                        asset = "USDC",
                    ),
                )
            } else {
                viewModel.receivedChatMessage(
                    ChatMessage(
                        sender = sender,
                        text = text,
                        timestamp = timestamp,
                    ),
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.viewEvent.collect { event ->
            when (event) {
                is LiquidStreamHostViewModel.ViewEvent.SendMessage -> onSendClick(event.message)
                is LiquidStreamHostViewModel.ViewEvent.ShowError -> Unit
                is LiquidStreamHostViewModel.ViewEvent.ToggleMic -> onMicClick(event.isMuted)
                is LiquidStreamHostViewModel.ViewEvent.ToggleCamera -> onCameraClick(event.isEnabled)
            }
        }
    }

    LiquidStreamHostLiveScreenContent(
        cameraPreview = cameraPreviewComp,
        creatorUsername = DebugAddressHolder.creatorAddress.toShortenedAddress(),
        numbersOfViewer = "1",
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
        viewers =
            listOf(
                ConnectedViewerInfo(
                    sessionId = "debug-session-1",
                    remainingBalanceUSDC = debugState.viewerBalances[DebugAddressHolder.viewerAddress] ?: 0.0,
                    progressBalanceUSDC = (debugState.viewerBalances[DebugAddressHolder.viewerAddress] ?: 0.0) * 0.8,
                    progressCapacityUSDC = debugState.viewerBalances[DebugAddressHolder.viewerAddress] ?: 0.0,
                    connectionType = IceConnectionType.RELAY,
                    currentBlockNumber = debugState.liveBlockNumber,
                    networkLabel = debugState.liveNetworkLabel,
                    originUrl = "https://viewer-1.app",
                    viewerAddress = DebugAddressHolder.viewerAddress,
                ),
                ConnectedViewerInfo(
                    sessionId = "debug-session-2",
                    remainingBalanceUSDC = debugState.viewerBalances[DebugAddressHolder.viewerAddress2] ?: 0.0,
                    progressBalanceUSDC = (debugState.viewerBalances[DebugAddressHolder.viewerAddress2] ?: 0.0) * 0.5,
                    progressCapacityUSDC = debugState.viewerBalances[DebugAddressHolder.viewerAddress2] ?: 0.0,
                    connectionType = IceConnectionType.STUN,
                    currentBlockNumber = debugState.liveBlockNumber,
                    networkLabel = debugState.liveNetworkLabel,
                    originUrl = "https://viewer-2.app",
                    viewerAddress = DebugAddressHolder.viewerAddress2,
                ),
                ConnectedViewerInfo(
                    sessionId = "debug-session-3",
                    remainingBalanceUSDC = debugState.viewerBalances[DebugAddressHolder.viewerAddress3] ?: 0.0,
                    progressBalanceUSDC = (debugState.viewerBalances[DebugAddressHolder.viewerAddress3] ?: 0.0) * 0.2,
                    progressCapacityUSDC = debugState.viewerBalances[DebugAddressHolder.viewerAddress3] ?: 0.0,
                    connectionType = IceConnectionType.LOCAL,
                    currentBlockNumber = debugState.liveBlockNumber,
                    networkLabel = debugState.liveNetworkLabel,
                    originUrl = "https://viewer-3.app",
                    viewerAddress = DebugAddressHolder.viewerAddress3,
                ),
            ).filter { !it.viewerAddress.isNullOrBlank() },
        blockChainLabel = blockChainLabel,
        balanceCurrencySymbol = balanceCurrencySymbol,
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
                        connectionType = IceConnectionType.UNKNOWN,
                        currentBlockNumber = 38291041L,
                        networkLabel = "TESTNET",
                        originUrl = "https://example.app",
                    ),
                ),
            blockChainLabel = "ALGORAND",
            balanceCurrencySymbol = "A",
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
