package com.michaeltchuang.walletsdk.ui.settings.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
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
import com.michaeltchuang.walletsdk.core.foundation.utils.LiquidStreamConstants
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.liquidAuth.screens.LiquidAuthOfferScreen
import com.michaeltchuang.walletsdk.ui.liquidAuth.screens.StreamHostUiMode
import com.michaeltchuang.walletsdk.ui.liquidAuth.service.LiquidAuthConnectionManager
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.LiquidAuthOfferViewModel
import com.michaeltchuang.walletsdk.ui.liquidStream.components.CameraStreamingPreviewController
import com.michaeltchuang.walletsdk.ui.liquidStream.components.createCameraStreamingPreview
import com.michaeltchuang.walletsdk.ui.liquidStream.utils.PAYOUT_BATCH_BLOCK_COUNT
import com.michaeltchuang.walletsdk.ui.liquidStream.utils.PAYOUT_EVERY_256_BLOCKS_TAB_ID
import com.michaeltchuang.walletsdk.ui.liquidStream.viewmodels.LiquidStreamHostViewModel
import com.michaeltchuang.walletsdk.ui.settings.utils.debug.LiveDebugPermissionGate
import com.michaeltchuang.walletsdk.ui.settings.utils.debug.rememberLiveDebugConnectionManager
import com.michaeltchuang.walletsdk.ui.settings.viewmodels.LiquidStreamLiveDebugViewModel
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun LiquidStreamHostDebugToolScreen(
    viewModel: LiquidStreamHostViewModel = koinViewModel(),
    debugViewModel: LiquidStreamLiveDebugViewModel = koinViewModel(),
    onMinimise: () -> Unit = {},
) {
    val offerViewModel: LiquidAuthOfferViewModel = koinViewModel()
    val manager = rememberLiveDebugConnectionManager()
    val uiState by viewModel.state.collectAsStateWithLifecycle()
    val state by debugViewModel.state.collectAsStateWithLifecycle()
    val streamHostUiMode = remember { mutableStateOf(StreamHostUiMode.Expanded) }
    val cameraController = remember { CameraStreamingPreviewController() }
    val cameraPreview =
        remember(manager, cameraController) {
            createCameraStreamingPreview(manager, cameraController)
        }

    fun stopHost() {
        manager.stopBlockConsumption()
        manager.stopListening()
        offerViewModel.stopRealtimeBlockNumberUpdates()
        offerViewModel.stopVideoStreaming()
        offerViewModel.clearMeshHosting()
        viewModel.resetMediaToggles()
    }

    DisposableEffect(debugViewModel, manager, offerViewModel) {
        onDispose {
            debugViewModel.stopBots()
            debugViewModel.closeAllSessions()
            stopHost()
        }
    }

    var hadLoaded by remember(debugViewModel, manager, offerViewModel) { mutableStateOf(false) }
    LaunchedEffect(debugViewModel, manager, offerViewModel, state.loaded) {
        if (state.loaded) {
            hadLoaded = true
        } else if (hadLoaded) {
            // The loading/error return below does not dispose the screen-owned manager.
            stopHost()
            hadLoaded = false
        }
    }

    val isPaid = uiState.selectedStreamCostTabId == LiquidStreamHostViewModel.STREAM_COST_PAID_TAB_ID
    val costMicroUsdc = if (isPaid) LiquidStreamConstants.COST_PER_BLOCK_MICRO_USDC else 0L
    val payoutBlocks =
        if (uiState.selectedPayoutFrequencyTabId == PAYOUT_EVERY_256_BLOCKS_TAB_ID) {
            PAYOUT_BATCH_BLOCK_COUNT
        } else {
            1
        }
    // Observe state rather than consuming host events also needed by the production UI.
    LaunchedEffect(debugViewModel, state.loaded, isPaid, costMicroUsdc, payoutBlocks) {
        if (state.loaded) debugViewModel.configure(isPaid, costMicroUsdc, payoutBlocks)
    }

    if (!state.loaded) {
        Text(
            text = state.error ?: "Loading debug viewers…",
            modifier = Modifier.padding(24.dp),
        )
        return
    }

    LiveDebugPermissionGate {
        LiveDebugOfferContent(
            state = state,
            debugViewModel = debugViewModel,
            manager = manager,
            cameraController = cameraController,
            cameraPreview = cameraPreview,
            streamHostUiMode = streamHostUiMode,
            isPaid = isPaid,
            costMicroUsdc = costMicroUsdc,
            payoutBlocks = payoutBlocks,
            onMinimise = onMinimise,
        )
    }
}

@Composable
private fun LiveDebugOfferContent(
    state: LiquidStreamLiveDebugViewModel.State,
    debugViewModel: LiquidStreamLiveDebugViewModel,
    manager: LiquidAuthConnectionManager,
    cameraController: CameraStreamingPreviewController,
    cameraPreview: @Composable () -> Unit,
    streamHostUiMode: MutableState<StreamHostUiMode>,
    isPaid: Boolean,
    costMicroUsdc: Long,
    payoutBlocks: Int,
    onMinimise: () -> Unit,
) {
    val isSolana = state.network.startsWith("solana", ignoreCase = true)
    LiquidAuthOfferScreen(
        origin = "https://liquid-auth-api.pg.nodely.dev/",
        onBackPressed = onMinimise,
        onMinimise = onMinimise,
        cameraPreview = cameraPreview,
        cameraPreviewController = cameraController,
        connectionManager = manager,
        streamHostUiModeState = streamHostUiMode,
        creatorAddress = state.creator,
        creatorAssetId = state.assetId,
        enablePaidStreaming = isPaid,
        paymentCurrencyLabel = if (isSolana) "SOL" else "ALGO",
        blockChainLabel = if (isSolana) "Solana" else "Algorand",
        balanceCurrencySymbol = if (isSolana) "S" else "A",
        paymentNetworkOverride = state.network,
        debugViewerDetails = state.botDetails,
        debugFullscreen = true,
        debugContent = {
            LiveDebugContent(state = state)
        },
    )

    // Entered only after loading and permission approval. Running/funding updates must not restart bots.
    LaunchedEffect(Unit) {
        debugViewModel.configure(isPaid, costMicroUsdc, payoutBlocks)
        debugViewModel.startBots { message -> manager.relayDebugViewerChat(message) }
    }
}

@Composable
private fun LiveDebugContent(state: LiquidStreamLiveDebugViewModel.State) {
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(15.dp))
                .background(
                    brush =
                        Brush.horizontalGradient(
                            colorStops =
                                arrayOf(
                                    0.00f to Color(0xFFAFEFF5),
                                    0.10f to Color(0x00AFEFF5),
                                    1.00f to Color(0x00AFEFF5),
                                ),
                        ),
                ).padding(start = 2.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(15.dp))
                    .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.isFunding) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = Color.White,
                )
            }
            Text(
                when {
                    state.error != null -> "System Bot billing: ${state.error}"
                    state.billingErrors.isNotEmpty() ->
                        state.billingErrors.entries.first().let {
                            "System Bot billing: ${it.key}: ${it.value}"
                        }
                    state.isFunding -> "Funding 1 USDC per bot…"
                    state.running -> "Bots running · ${state.botDetails.size} debug viewers"
                    state.isFundAdded && state.fundedAddresses.isNotEmpty() ->
                        "Fund added successfully to ${state.fundedAddresses.joinToString(", ")}"
                    else -> "Bots stopped"
                },
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
            )
        }
    }
}

@Preview
@Composable
fun LiveDebugContentPreview() {
    AlgoKitTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LiveDebugContent(
                state =
                    LiquidStreamLiveDebugViewModel.State(
                        running = true,
                    ),
            )
            LiveDebugContent(
                state =
                    LiquidStreamLiveDebugViewModel.State(
                        isFunding = true,
                    ),
            )
            LiveDebugContent(
                state =
                    LiquidStreamLiveDebugViewModel.State(
                        isFundAdded = true,
                        fundedAddresses = listOf("0x123...abcabbdjabdbabdjabdjabjhdbjbdjabjdbajhbdhjabj abdaj abdab"),
                    ),
            )
            LiveDebugContent(
                state =
                    LiquidStreamLiveDebugViewModel.State(
                        running = false,
                    ),
            )
        }
    }
}
