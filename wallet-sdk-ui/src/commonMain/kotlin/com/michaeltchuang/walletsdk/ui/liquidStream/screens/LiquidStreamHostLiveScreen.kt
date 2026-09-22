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
import androidx.compose.runtime.key
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
import com.michaeltchuang.walletsdk.core.foundation.utils.toShortenedAddress
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.model.HostViewerDetails
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.model.IceConnectionType
import com.michaeltchuang.walletsdk.ui.liquidAuth.service.LiquidAuthConnectionManager
import com.michaeltchuang.walletsdk.ui.liquidStream.components.ChatStack
import com.michaeltchuang.walletsdk.ui.liquidStream.components.ConnectedViewerInfo
import com.michaeltchuang.walletsdk.ui.liquidStream.components.ConnectedViewersCard
import com.michaeltchuang.walletsdk.ui.liquidStream.components.CreatorActionRow
import com.michaeltchuang.walletsdk.ui.liquidStream.components.CreatorComposer
import com.michaeltchuang.walletsdk.ui.liquidStream.components.CreatorTopBar
import com.michaeltchuang.walletsdk.ui.liquidStream.components.HomeIndicator
import com.michaeltchuang.walletsdk.ui.liquidStream.components.LiquidStreamHostQrModal
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
    creatorUsername: String? = null,
    creatorAvatarUrl: String? = null,
    creatorAddress: String = "",
    viewerAddress: String = "",
    numbersOfViewer: String = "1",
    lastSettledUsdc: Double? = null,
    requestId: String = "",
    liquidAuthUrl: String = "",
    meshViewerIds: List<String>? = null,
    onRefreshInvitation: (() -> Unit)? = null,
    meshViewerDetails: Map<String, HostViewerDetails> = emptyMap(),
) {
    val uiState = viewModel.state.collectAsStateWithLifecycle().value
    val viewerCount = meshViewerIds?.size?.toString() ?: numbersOfViewer
    var prevRemainingBalanceUsdc by remember(sessionId) { mutableDoubleStateOf(remainingBalanceUsdc ?: 0.0) }
    var revenueCapacityUsdc by remember(sessionId) { mutableDoubleStateOf(remainingBalanceUsdc ?: 0.0) }
    var progressCapacityUsdc by remember(sessionId) { mutableDoubleStateOf(remainingBalanceUsdc ?: 0.0) }

    LaunchedEffect(creatorAddress, creatorUsername) {
        val addrToLoad = creatorAddress.ifBlank { creatorUsername.orEmpty() }
        if (addrToLoad.isNotBlank()) {
            viewModel.loadCreatorNfdProfile(addrToLoad)
        }
    }

    LaunchedEffect(remainingBalanceUsdc) {
        val current = remainingBalanceUsdc ?: 0.0
        val previous = prevRemainingBalanceUsdc
        if (current > previous) {
            revenueCapacityUsdc += (current - previous)
            progressCapacityUsdc = current
        }
        prevRemainingBalanceUsdc = current
    }

    val resolvedCreatorUsername =
        uiState.creatorNfdName
            ?: creatorAddress.toShortenedAddress()

    val resolvedCreatorAvatarUrl =
        uiState.creatorNfdAvatarUrl ?: creatorAvatarUrl

    val rawViewers =
        remember(
            sessionId,
            remainingBalanceUsdc,
            progressBalanceUsdc,
            progressCapacityUsdc,
            revenueCapacityUsdc,
            connectionType,
            currentBlockNumber,
            networkLabel,
            originUrl,
            lastSettledUsdc,
            viewerAddress,
            meshViewerIds,
            meshViewerDetails,
        ) {
            val primaryViewer =
                ConnectedViewerInfo(
                    sessionId = sessionId ?: "session-pending",
                    remainingBalanceUSDC = remainingBalanceUsdc,
                    progressBalanceUSDC = progressBalanceUsdc,
                    progressCapacityUSDC = progressCapacityUsdc,
                    revenueCapacityUSDC = revenueCapacityUsdc,
                    connectionType = connectionType,
                    currentBlockNumber = currentBlockNumber,
                    networkLabel = networkLabel,
                    originUrl = originUrl,
                    viewerAddress = viewerAddress,
                    lastSettledUSDC = lastSettledUsdc,
                )
            mapHostViewers(primaryViewer, meshViewerIds, meshViewerDetails)
        }

    val viewerAddresses = rawViewers.mapNotNull { it.viewerAddress?.takeIf(String::isNotBlank) }.distinct()
    LaunchedEffect(viewerAddresses) {
        viewerAddresses.forEach(viewModel::loadViewerNfdProfile)
    }
    val viewers =
        rawViewers.map { viewer ->
            viewer.copy(viewerAddress = hostViewerDisplayAddress(viewer.viewerAddress, uiState.viewerNfdNames))
        }

    LaunchedEffect(
        currentBlockNumber,
        blockChainLabel,
        networkLabel,
        viewerCount,
        viewers,
    ) {
        viewModel.updateMetrics(
            currentBlockNumber = currentBlockNumber,
            blockChainLabel = blockChainLabel,
            networkLabel = networkLabel,
            numbersOfViewer = viewerCount,
            viewers = viewers,
        )
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
                is LiquidStreamHostViewModel.ViewEvent.StreamCostChanged -> Unit
                is LiquidStreamHostViewModel.ViewEvent.PayoutFrequencyChanged -> {
                    connectionManager?.setPayoutFrequency(event.tabId)
                }
            }
        }
    }

    LiquidStreamHostLiveScreenContent(
        cameraPreview = cameraPreview,
        creatorUsername = resolvedCreatorUsername,
        creatorAvatarUrl = resolvedCreatorAvatarUrl,
        numbersOfViewer = viewerCount,
        onSettingsClick = {
            viewModel.onSettingsClicked()
            onStatsModalVisibilityChanged(false)
            onSettingsClick()
        },
        onMinimise = onMinimise,
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
        viewers = viewers,
        blockChainLabel = blockChainLabel,
        balanceCurrencySymbol = balanceCurrencySymbol,
        streamRevenue = uiState.streamRevenue,
        blockNumberLabel = uiState.blockNumberLabel,
        securedViaLabel = uiState.securedViaLabel,
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
        onQrClick = {
            if (liquidAuthUrl.isBlank()) onRefreshInvitation?.invoke()
            viewModel.onQrClicked()
        },
        onQrDismissed = viewModel::onQrDismissed,
        requestId = requestId,
        liquidAuthUrl = liquidAuthUrl,
        showQrButton = liquidAuthUrl.isNotBlank() || onRefreshInvitation != null,
    )
}

/**
 * Keep singleton statistics attached to the original session, even after it leaves.
 * Additional peers use only their own snapshots; shared network/origin metadata is safe to reuse.
 */
internal fun mapHostViewers(
    primaryViewer: ConnectedViewerInfo,
    meshViewerIds: List<String>?,
    meshViewerDetails: Map<String, HostViewerDetails>,
): List<ConnectedViewerInfo> =
    meshViewerIds?.map { requestId ->
        val details = meshViewerDetails[requestId] ?: HostViewerDetails()
        if (requestId == primaryViewer.sessionId) {
            primaryViewer.copy(
                viewerAddress = primaryViewer.viewerAddress?.takeIf(String::isNotBlank) ?: details.viewerAddress,
                connectionType =
                    primaryViewer.connectionType.takeUnless { it == IceConnectionType.UNKNOWN }
                        ?: details.connectionType,
                remainingBalanceUSDC = primaryViewer.remainingBalanceUSDC ?: details.remainingBalanceMicroUsdc?.let { it / 1_000_000.0 },
                progressBalanceUSDC = primaryViewer.progressBalanceUSDC ?: details.progressBalanceMicroUsdc?.let { it / 1_000_000.0 },
                lastSettledUSDC = primaryViewer.lastSettledUSDC ?: details.lastSettledMicroUsdc?.let { it / 1_000_000.0 },
            )
        } else {
            ConnectedViewerInfo(
                sessionId = requestId,
                viewerAddress = details.viewerAddress,
                remainingBalanceUSDC = details.remainingBalanceMicroUsdc?.let { it / 1_000_000.0 },
                progressBalanceUSDC = details.progressBalanceMicroUsdc?.let { it / 1_000_000.0 },
                progressCapacityUSDC = (details.progressCapacityMicroUsdc ?: details.totalDepositMicroUsdc)?.let { it / 1_000_000.0 },
                revenueCapacityUSDC = details.totalDepositMicroUsdc?.let { it / 1_000_000.0 },
                lastSettledUSDC = details.lastSettledMicroUsdc?.let { it / 1_000_000.0 },
                connectionType = details.connectionType,
                currentBlockNumber = primaryViewer.currentBlockNumber,
                networkLabel = primaryViewer.networkLabel,
                originUrl = primaryViewer.originUrl,
            )
        }
    } ?: listOf(primaryViewer)

internal fun hostViewerDisplayAddress(
    address: String?,
    viewerNfdNames: Map<String, String>,
): String =
    address?.takeIf(String::isNotBlank)?.let {
        viewerNfdNames[it]?.takeIf(String::isNotBlank) ?: it.toShortenedAddress()
    } ?: "N/A"

@Composable
fun LiquidStreamHostLiveScreenContent(
    cameraPreview: @Composable (() -> Unit)?,
    creatorUsername: String?,
    creatorAvatarUrl: String? = null,
    numbersOfViewer: String?,
    onSettingsClick: () -> Unit,
    onMinimise: () -> Unit,
    onCameraClick: () -> Unit,
    onMicClick: () -> Unit,
    onRotateCamera: () -> Unit,
    onStatsClick: () -> Unit,
    onSendClickInternal: () -> Unit,
    viewers: List<ConnectedViewerInfo>,
    blockChainLabel: String,
    balanceCurrencySymbol: String,
    streamRevenue: String,
    blockNumberLabel: String,
    securedViaLabel: String = "ALGORAND TESTNET",
    uiState: LiquidStreamHostViewModel.UiState,
    onTextChanged: (String) -> Unit,
    onStatsDismissed: () -> Unit,
    onStreamCostTabSelected: (String) -> Unit,
    onPayoutFrequencyTabSelected: (String) -> Unit,
    onSubsidizeViewerFeesChanged: (Boolean) -> Unit,
    onSettingsDismissed: () -> Unit,
    onQrClick: () -> Unit = {},
    onQrDismissed: () -> Unit = {},
    requestId: String = "",
    liquidAuthUrl: String = "",
    showQrButton: Boolean = false,
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
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp)
                    .imePadding(),
        ) {
            CreatorTopBar(
                creatorUsername = creatorUsername,
                creatorAvatarUrl = creatorAvatarUrl,
                numbersOfViewers = numbersOfViewer,
                onSettingsClick = onSettingsClick,
                onMinimise = onMinimise,
            )
            Spacer(Modifier.weight(1f))
            ChatStack(uiState.chatMessages)
            Spacer(Modifier.height(18.dp))
            CreatorActionRow(
                onQRClick = onQrClick,
                onCameraClick = onCameraClick,
                onMicClick = onMicClick,
                onRotateCamera = onRotateCamera,
                onStatsClick = onStatsClick,
                isMicMuted = uiState.isMicMuted,
                isCameraEnabled = uiState.isCameraEnabled,
                showQrButton = showQrButton,
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
                    ConnectedViewersCard(viewers = viewers)
                }
            }
        }

        if (uiState.isSettingsModalVisible) {
            StreamHostSettingsSheet(
                selectedStreamCostTabId = uiState.selectedStreamCostTabId,
                selectedPayoutFrequencyTabId = uiState.selectedPayoutFrequencyTabId,
                subsidizeViewerFeesEnabled = uiState.subsidizeViewerFeesEnabled,
                realTimeRate = uiState.realTimeRate,
                streamRevenue = streamRevenue,
                securedViaLabel = securedViaLabel,
                blockNumberLabel = blockNumberLabel,
                onStreamCostTabSelected = onStreamCostTabSelected,
                onPayoutFrequencyTabSelected = onPayoutFrequencyTabSelected,
                onSubsidizeViewerFeesChanged = onSubsidizeViewerFeesChanged,
                onDismiss = onSettingsDismissed,
            )
        }

        if (uiState.isQrModalVisible && liquidAuthUrl.isNotBlank()) {
            // Keep the original QR modal; update it when the next viewer invitation rotates.
            key(requestId, liquidAuthUrl) {
                LiquidStreamHostQrModal(
                    requestId = requestId,
                    qrUrl = liquidAuthUrl,
                    securedViaLabel = securedViaLabel,
                    onDismiss = onQrDismissed,
                )
            }
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
            onSettingsClick = {
                uiState =
                    uiState.copy(
                        isSettingsModalVisible = true,
                        isStatsModalVisible = false,
                        isQrModalVisible = false,
                    )
            },
            onMinimise = {},
            onCameraClick = { uiState = uiState.copy(isCameraEnabled = !uiState.isCameraEnabled) },
            onMicClick = { uiState = uiState.copy(isMicMuted = !uiState.isMicMuted) },
            onRotateCamera = {},
            onStatsClick = {
                uiState =
                    uiState.copy(
                        isStatsModalVisible = !uiState.isStatsModalVisible,
                        isSettingsModalVisible = false,
                        isQrModalVisible = false,
                    )
            },
            onQrClick = {
                uiState =
                    uiState.copy(isQrModalVisible = !uiState.isQrModalVisible, isSettingsModalVisible = false, isStatsModalVisible = false)
            },
            onQrDismissed = { uiState = uiState.copy(isQrModalVisible = false) },
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
