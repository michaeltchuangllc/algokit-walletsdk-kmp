package com.michaeltchuang.walletsdk.ui.liquidStream.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.model.IceConnectionType
import com.michaeltchuang.walletsdk.ui.liquidAuth.service.LiquidAuthConnectionManager
import com.michaeltchuang.walletsdk.ui.liquidStream.components.ChatStack
import com.michaeltchuang.walletsdk.ui.liquidStream.components.ConnectedViewersCard
import com.michaeltchuang.walletsdk.ui.liquidStream.components.CreatorActionRow
import com.michaeltchuang.walletsdk.ui.liquidStream.components.CreatorComposer
import com.michaeltchuang.walletsdk.ui.liquidStream.components.CreatorTopBar
import com.michaeltchuang.walletsdk.ui.liquidStream.components.HomeIndicator
import com.michaeltchuang.walletsdk.ui.liquidStream.viewmodels.LiquidStreamHostViewModel
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun LiquidStreamHostLiveScreen(
    cameraPreview: @Composable (() -> Unit)? = null,
    connectionManager: LiquidAuthConnectionManager? = null,
    viewModel: LiquidStreamHostViewModel = koinViewModel(),
    onSettingsClick: () -> Unit = {},
    onMinimise: () -> Unit = {},
    onWalletClick: () -> Unit = {},
    onCameraClick: (isEnabled: Boolean) -> Unit = {},
    onMicClick: (isMuted: Boolean) -> Unit = {},
    onRotateCamera: () -> Unit = {},
    onStatsClick: () -> Unit = {},
    onStatsModalVisibilityChanged: (Boolean) -> Unit = {},
    onSendClick: (String) -> Unit = {},
    sessionId: String? = null,
    progressBalanceUsdc: Double? = null,
    remainingBalanceUsdc: Double? = progressBalanceUsdc,
    connectionType: IceConnectionType = IceConnectionType.UNKNOWN,
    currentBlockNumber: Long? = null,
    blockChainLabel: String = "ALGORAND",
    networkLabel: String = "TESTNET",
    balanceCurrencySymbol: String = "¦",
    originUrl: String = "-",
    creatorUsername: String = "michaeltchuang.algo",
    numbersOfViewer: String = "1",
) {
    val uiState = viewModel.state.collectAsStateWithLifecycle().value
    var progressCapacityUsdc by remember(sessionId) { mutableDoubleStateOf(0.0) }

    LaunchedEffect(remainingBalanceUsdc) {
        val balance = remainingBalanceUsdc ?: 0.0
        if (balance > progressCapacityUsdc) {
            progressCapacityUsdc = balance
        }
    }

    LaunchedEffect(Unit) {
        viewModel.viewEvent.collect { event ->
            when (event) {
                is LiquidStreamHostViewModel.ViewEvent.SendMessage -> onSendClick(event.message)
                is LiquidStreamHostViewModel.ViewEvent.ShowError -> Unit
                is LiquidStreamHostViewModel.ViewEvent.ToggleMic -> {
                    connectionManager?.setAudioEnabled(!event.isMuted)
                    onMicClick(event.isMuted)
                }
                is LiquidStreamHostViewModel.ViewEvent.ToggleCamera -> {
                    connectionManager?.setVideoEnabled(event.isEnabled)
                    onCameraClick(event.isEnabled)
                }
            }
        }
    }

    LiquidStreamHostLiveScreenContent(
        cameraPreview = cameraPreview,
        creatorUsername = creatorUsername,
        numbersOfViewer = numbersOfViewer,
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
        sessionId = sessionId,
        progressBalanceUsdc = progressBalanceUsdc,
        remainingBalanceUsdc = remainingBalanceUsdc,
        progressCapacityUsdc = progressCapacityUsdc,
        connectionType = connectionType,
        currentBlockNumber = currentBlockNumber,
        blockChainLabel = blockChainLabel,
        networkLabel = networkLabel,
        balanceCurrencySymbol = balanceCurrencySymbol,
        originUrl = originUrl,
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

@Composable
fun LiquidStreamHostLiveScreenContent(
    cameraPreview: @Composable (() -> Unit)?,
    creatorUsername: String ?,
    numbersOfViewer: String ?,
    onSettingsClick: () -> Unit,
    onMinimise: () -> Unit,
    onWalletClick: () -> Unit,
    onCameraClick: () -> Unit,
    onMicClick: () -> Unit,
    onRotateCamera: () -> Unit,
    onStatsClick: () -> Unit,
    onSendClickInternal: () -> Unit,
    sessionId: String?,
    progressBalanceUsdc: Double?,
    remainingBalanceUsdc: Double?,
    progressCapacityUsdc: Double,
    connectionType: IceConnectionType,
    currentBlockNumber: Long?,
    blockChainLabel: String,
    networkLabel: String,
    balanceCurrencySymbol: String,
    originUrl: String,
    uiState: LiquidStreamHostViewModel.UiState,
    onTextChanged: (String) -> Unit,
    onStatsDismissed: () -> Unit,
    onStreamCostTabSelected: (String) -> Unit,
    onPayoutFrequencyTabSelected: (String) -> Unit,
    onSubsidizeViewerFeesChanged: (Boolean) -> Unit,
    onSettingsDismissed: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color(0xFF76818D)),
    ) {
        // Only render the local camera preview when the camera is enabled.
        if (cameraPreview != null && uiState.isCameraEnabled) {
            cameraPreview()
        }

        Box(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(520.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color(0x008A9AA9), Color(0x9F23384E), Color(0xD010263D)),
                        ),
                    ),
        )

        Box(
            modifier =
                Modifier
                    .size(width = 260.dp, height = 102.dp)
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 256.dp)
                    .clip(RoundedCornerShape(topStart = 120.dp, topEnd = 120.dp))
                    .background(Color(0x12FFFFFF)),
        )

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 0.dp)
                    .imePadding(),
        ) {
            CreatorTopBar(
                creatorUsername = creatorUsername,
                numbersOfViewers = numbersOfViewer,
                onSettingsClick = onSettingsClick,
                onMinimise = onMinimise,
            )
            Spacer(Modifier.weight(1f))
            ChatStack(uiState.chatMessages)
            Spacer(Modifier.height(18.dp))
            CreatorActionRow(
                onWalletClick = onWalletClick,
                onCameraClick = onCameraClick,
                onMicClick = onMicClick,
                onRotateCamera = onRotateCamera,
                onStatsClick = onStatsClick,
                isMicMuted = uiState.isMicMuted,
                isCameraEnabled = uiState.isCameraEnabled,
            )
            Spacer(Modifier.height(18.dp))
            CreatorComposer(
                text = uiState.message,
                onTextChanged = onTextChanged,
                onSendClick = onSendClickInternal,
            )
            Spacer(Modifier.height(20.dp))
            HomeIndicator()
            Spacer(Modifier.height(4.dp))
        }

        if (uiState.isStatsModalVisible) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color(0x4D001423))
                        .clickable { onStatsDismissed() },
            )

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Bottom,
            ) {
                Box(
                    modifier = Modifier.padding(bottom = 250.dp),
                ) {
                    ConnectedViewersCard(
                        sessionId = sessionId ?: "session-pending",
                        remainingBalanceUSDC = remainingBalanceUsdc,
                        progressBalanceUSDC = progressBalanceUsdc,
                        progressCapacityUSDC = progressCapacityUsdc,
                        connectionType = connectionType,
                        currentBlockNumber = currentBlockNumber,
                        networkLabel = networkLabel,
                        originUrl = originUrl,
                    )
                }
            }
        }

        if (uiState.isSettingsModalVisible) {
            StreamHostSettingsSheet(
                selectedStreamCostTabId = uiState.selectedStreamCostTabId,
                selectedPayoutFrequencyTabId = uiState.selectedPayoutFrequencyTabId,
                subsidizeViewerFeesEnabled = uiState.subsidizeViewerFeesEnabled,
                realTimeRate = uiState.realTimeRate,
                streamRevenue = uiState.streamRevenue,
                securedViaLabel = uiState.securedViaLabel,
                blockNumberLabel = uiState.blockNumberLabel,
                onStreamCostTabSelected = onStreamCostTabSelected,
                onPayoutFrequencyTabSelected = onPayoutFrequencyTabSelected,
                onSubsidizeViewerFeesChanged = onSubsidizeViewerFeesChanged,
                onDismiss = onSettingsDismissed,
            )
        }
    }
}

@Preview
@Composable
private fun LiquidStreamHostLiveScreenPreview() {
    AlgoKitTheme {
        var uiState by remember { mutableStateOf(LiquidStreamHostViewModel.UiState()) }
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
            sessionId = "session-preview-id",
            progressBalanceUsdc = 11.9,
            remainingBalanceUsdc = 12.0,
            progressCapacityUsdc = 12.0,
            connectionType = IceConnectionType.UNKNOWN,
            currentBlockNumber = 38291041L,
            blockChainLabel = "ALGORAND",
            networkLabel = "TESTNET",
            balanceCurrencySymbol = "A",
            originUrl = "https://example.app",
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
