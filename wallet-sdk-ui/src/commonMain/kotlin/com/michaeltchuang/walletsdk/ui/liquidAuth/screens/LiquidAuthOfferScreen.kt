package com.michaeltchuang.walletsdk.ui.liquidAuth.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.michaeltchuang.walletsdk.ui.liquidAuth.components.CameraStreamingPreviewController
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.liquidAuth.components.ConnectedViewersCard
import com.michaeltchuang.walletsdk.ui.liquidAuth.model.IceConnectionType
import com.michaeltchuang.walletsdk.ui.liquidAuth.model.X402PaymentMessages
import com.michaeltchuang.walletsdk.ui.liquidAuth.model.colorHex
import com.michaeltchuang.walletsdk.ui.liquidAuth.model.costTier
import com.michaeltchuang.walletsdk.ui.liquidAuth.model.displayName
import com.michaeltchuang.walletsdk.ui.liquidAuth.model.typicalLatency
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.LiquidAuthOfferViewModel
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.koin.compose.viewmodel.koinViewModel
import qrgenerator.qrkitpainter.rememberQrKitPainter

enum class StreamHostUiMode {
    Hidden,
    Expanded,
    Minimized,
}

@Composable
fun LiquidAuthMiniPlayerOverlay(
    streamHostUiModeState: MutableState<StreamHostUiMode>,
    miniPlayerCameraPreviewState: MutableState<(@Composable () -> Unit)?>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    miniPlayerSize: DpSize = DpSize(width = 180.dp, height = 320.dp),
    defaultMargin: Float? = null,
    defaultBottomPadding: Float? = null,
) {
    val density = LocalDensity.current
    var containerSizePx by remember { mutableStateOf(IntSize.Zero) }
    var miniPlayerOffsetPx by remember { mutableStateOf<Offset?>(null) }
    val defaultMarginPx = defaultMargin ?: with(density) { 16.dp.toPx() }
    val defaultBottomPaddingPx = defaultBottomPadding ?: with(density) { 132.dp.toPx() }
    val miniPlayerWidthPx = with(density) { miniPlayerSize.width.toPx() }
    val miniPlayerHeightPx = with(density) { miniPlayerSize.height.toPx() }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .onSizeChanged { containerSizePx = it },
    ) {
        if (streamHostUiModeState.value == StreamHostUiMode.Minimized) {
            val maxX = (containerSizePx.width - miniPlayerWidthPx).coerceAtLeast(0f)
            val maxY = (containerSizePx.height - miniPlayerHeightPx).coerceAtLeast(0f)
            val anchoredOffset =
                miniPlayerOffsetPx
                    ?: Offset(
                        x = (containerSizePx.width - miniPlayerWidthPx - defaultMarginPx).coerceAtLeast(0f),
                        y = (containerSizePx.height - miniPlayerHeightPx - defaultBottomPaddingPx).coerceAtLeast(0f),
                    )
            val clampedOffset =
                Offset(
                    x = anchoredOffset.x.coerceIn(0f, maxX),
                    y = anchoredOffset.y.coerceIn(0f, maxY),
                )
            if (miniPlayerOffsetPx != clampedOffset) {
                miniPlayerOffsetPx = clampedOffset
            }

            Box(
                modifier =
                    Modifier
                        .offset {
                            IntOffset(
                                x = clampedOffset.x.toInt(),
                                y = clampedOffset.y.toInt(),
                            )
                        }
                        .size(miniPlayerSize)
                        .shadow(8.dp, RoundedCornerShape(16.dp))
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black)
                        .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(16.dp))
                        .pointerInput(containerSizePx, miniPlayerSize) {
                            detectDragGestures(
                                onDragStart = {},
                                onDragEnd = {},
                                onDragCancel = {},
                            ) { _, dragAmount ->
                                val current = miniPlayerOffsetPx ?: clampedOffset
                                miniPlayerOffsetPx =
                                    Offset(
                                        x = (current.x + dragAmount.x).coerceIn(0f, maxX),
                                        y = (current.y + dragAmount.y).coerceIn(0f, maxY),
                                    )
                            }
                        }
                        .clickable {
                            streamHostUiModeState.value = StreamHostUiMode.Expanded
                        },
            ) {
                miniPlayerCameraPreviewState.value?.invoke()
                IconButton(
                    onClick = {
                        streamHostUiModeState.value = StreamHostUiMode.Hidden
                        onClose()
                    },
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(28.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close mini player",
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

/**
 * Liquid Auth Offer Screen with WebRTC video streaming support
 *
 * This screen generates a QR code that dApps can scan to initiate
 * a Liquid Auth connection. Once connected, it can stream video
 * back to the client over WebRTC data channels.
 *
 * This is a self-contained component that manages:
 * - QR code generation for peer connection
 * - WebRTC SignalService binding (Android)
 * - Connection state detection and UI transitions
 * - Video streaming UI
 *
 * @param origin The origin URL of the liquid auth service (e.g., https://auth.example.com)
 * @param title The title composable to display in the app bar
 * @param onBackPressed Callback when user presses back
 * @param cameraPreview Optional camera preview composable slot for streaming
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiquidAuthOfferScreen(
    origin: String,
    title: @Composable () -> Unit = {},
    onBackPressed: (() -> Unit)? = null,
    cameraPreview: @Composable (() -> Unit)? = null,
    connectionManager: com.michaeltchuang.walletsdk.ui.liquidAuth.service.LiquidAuthConnectionManager? = null,
    showTopBar: Boolean = false,
    headerContent: @Composable (() -> Unit)? = null,
    creatorAddress: String? = null, // For X402 paid streaming
    enablePaidStreaming: Boolean = false, // Toggle X402 payments
    paymentCurrencyLabel: String = "ALGO",
    blockChainLabel: String = "Algorand",
    balanceCurrencySymbol: String = "S",
    onMinimise: () -> Unit = {},
    streamHostUiModeState: MutableState<StreamHostUiMode>? = null,
    miniPlayerCameraPreviewState: MutableState<(@Composable () -> Unit)?>? = null,
    miniPlayerOnCloseActionState: MutableState<(() -> Unit)?>? = null,
    cameraPreviewController: CameraStreamingPreviewController? = null,
) {
    val viewModel: LiquidAuthOfferViewModel = koinViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val paymentState by viewModel.paymentState.collectAsStateWithLifecycle()
    val connectionType by viewModel.connectionType.collectAsStateWithLifecycle()
    val remainingBalanceMicroAlgos by viewModel.remainingBalanceMicroAlgos.collectAsStateWithLifecycle()
    val currentBlockNumber by viewModel.currentBlockNumber.collectAsStateWithLifecycle()
    val currentNetworkLabel by viewModel.currentNetworkLabel.collectAsStateWithLifecycle()
    val streamHostUiMode = streamHostUiModeState ?: remember { mutableStateOf(StreamHostUiMode.Hidden) }
    val isAnalyticsModalVisible = remember { mutableStateOf(false) }
    val resolvedCameraPreviewController = cameraPreviewController ?: remember { CameraStreamingPreviewController() }
    miniPlayerCameraPreviewState?.value = cameraPreview
    miniPlayerOnCloseActionState?.value = {
        viewModel.stopVideoStreaming()
        connectionManager?.stopBlockConsumption()
        connectionManager?.stopListening()
        streamHostUiMode.value = StreamHostUiMode.Hidden
        viewModel.regenerateOffer(origin)
    }

    // Convert microAlgos to AlgOS for display
    val balanceAlgos = remainingBalanceMicroAlgos?.let { it / 1_000_000.0 }

    // Auto-generate offer on first composition
    LaunchedEffect(origin) {
        viewModel.generateOffer(origin)
    }

    // Wire up connection manager to ViewModel - CRITICAL: must happen before listening
    LaunchedEffect(connectionManager, viewModel) {
        if (connectionManager != null) {
            println("🔗 Initializing connection manager with viewModel")
            connectionManager.initialize(viewModel)
            println("🔗 Connection manager initialized")
        }
    }

    LaunchedEffect(isAnalyticsModalVisible.value, state, streamHostUiMode.value, paymentState) {
        when (state) {
            is LiquidAuthOfferViewModel.OfferState.Connected,
            is LiquidAuthOfferViewModel.OfferState.Streaming,
            is LiquidAuthOfferViewModel.OfferState.WaitingForPayment,
                -> {
                if (isAnalyticsModalVisible.value && streamHostUiMode.value == StreamHostUiMode.Expanded) {
                    viewModel.startRealtimeBlockNumberUpdates()
                } else {
                    viewModel.stopRealtimeBlockNumberUpdates()
                }

                // Ensure billing/deduction loop is running whenever paid streaming is active.
                if (paymentState is LiquidAuthOfferViewModel.PaymentState.StreamingWithBalance) {
                    viewModel.getCurrentSessionId()?.let { sessionId ->
                        connectionManager?.startBlockConsumption(sessionId)
                    }
                }
            }
            else -> {
                viewModel.stopRealtimeBlockNumberUpdates()
                connectionManager?.stopBlockConsumption()
            }
        }
    }

    // Start listening after initialization
    LaunchedEffect(state, connectionManager) {
        // Ensure connectionManager is initialized before starting
        if (connectionManager == null) {
            println("⚠️ Cannot start listening - connectionManager is null")
            return@LaunchedEffect
        }

        val currentState = state
        println(
            "🔗 State changed to: ${currentState::class.simpleName}, " +
                    "isConnected=${connectionManager.isConnected()}, enablePaidStreaming=$enablePaidStreaming, creatorAddress=$creatorAddress",
        )

        when (currentState) {
            is LiquidAuthOfferViewModel.OfferState.WaitingForConnection -> {
                println("🔗 Starting listening for requestId: ${currentState.requestId}")
                connectionManager.startListening(
                    origin = currentState.origin,
                    requestId = currentState.requestId,
                )
            }

            is LiquidAuthOfferViewModel.OfferState.Connected,
            is LiquidAuthOfferViewModel.OfferState.Streaming,
                -> {
                println("🔗 Connection established - service handling")
                if (isAnalyticsModalVisible.value) {
                    viewModel.startRealtimeBlockNumberUpdates()
                } else {
                    viewModel.stopRealtimeBlockNumberUpdates()
                }
            }

            is LiquidAuthOfferViewModel.OfferState.WaitingForPayment -> {
                println("🔗 Waiting for payment - keeping connection open")
                if (isAnalyticsModalVisible.value) {
                    viewModel.startRealtimeBlockNumberUpdates()
                } else {
                    viewModel.stopRealtimeBlockNumberUpdates()
                }
            }

            else -> {
                println("🔗 Stopping listening - state: ${currentState::class.simpleName}")
                viewModel.stopRealtimeBlockNumberUpdates()
                connectionManager.stopListening()
            }
        }
    }

    // Handle X402 PaymentRequested event - only depend on viewModel to avoid restarts
    // Use rememberUpdatedState to always get latest parameter values
    val currentEnablePaidStreaming by rememberUpdatedState(enablePaidStreaming)
    val currentCreatorAddress by rememberUpdatedState(creatorAddress)
    val currentConnectionManager by rememberUpdatedState(connectionManager)

    LaunchedEffect(viewModel) {
        viewModel.viewEvent.collect { event ->
            when (event) {
                is LiquidAuthOfferViewModel.OfferEvent.ClientConnected -> {
                    // Capture values in local variables for smart cast
                    val canRequestPayment = currentEnablePaidStreaming
                    val address = currentCreatorAddress
                    println("💰 ClientConnected event received! enablePaidStreaming=$canRequestPayment, creatorAddress=$address")
                    // Auto-request payment when paid streaming is enabled
                    if (canRequestPayment && address != null) {
                        println("💰 Requesting payment from client...")
                        viewModel.requestPaymentFromClient(address)
                    } else {
                        println(
                            "💰 Paid streaming disabled or no creatorAddress (enablePaidStreaming=$canRequestPayment, creatorAddress=$address)",
                        )
                    }
                }

                is LiquidAuthOfferViewModel.OfferEvent.PaymentRequested -> {
                    // Send payment request to client via data channel
                    val connected = currentConnectionManager?.isConnected()
                    println("💰 PaymentRequested event received, sending via connectionManager... isConnected=$connected")
                    currentConnectionManager?.sendPaymentRequest(event.paymentRequest)
                    println("💰 Payment request sent: ${event.paymentRequest.amountMicroAlgos} microAlgos")
                }

                is LiquidAuthOfferViewModel.OfferEvent.PaymentReceived -> {
                    // Start block consumption when payment received
                    println("💰 PaymentReceived event received!")
                    // Ensure only one block poller is active (<= 1 network call per second)
                    viewModel.stopRealtimeBlockNumberUpdates()
                    val sessionId = viewModel.getCurrentSessionId()
                    if (sessionId != null) {
                        currentConnectionManager?.startBlockConsumption(sessionId)
                        println("💰 Payment received! Starting block consumption")
                    }
                }

                is LiquidAuthOfferViewModel.OfferEvent.FundsDepleted -> {
                    // Stop billing loop, but keep host UI mounted so stream screen doesn't close/reopen.
                    currentConnectionManager?.stopBlockConsumption()
                    viewModel.stopVideoStreaming() // Transition to Connected while keeping live host UI visible
                    println("💰 Funds depleted! Stopping stream and block consumption")
                    println("💰 Viewer must pay again to resume streaming")
                    if (streamHostUiMode.value == StreamHostUiMode.Hidden) {
                        streamHostUiMode.value = StreamHostUiMode.Expanded
                    }
                    currentCreatorAddress?.let { address ->
                        println("💰 Requesting additional payment from viewer...")
                        viewModel.requestPaymentFromClient(address)
                    }
                }

                else -> { /* other events */
                }
            }
        }
    }

    // Cleanup
    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopRealtimeBlockNumberUpdates()
            connectionManager?.stopListening()
            connectionManager?.stopBlockConsumption()
        }
    }

    LiquidAuthOfferScreenContent(
        showTopBar = showTopBar,
        title = title,
        onBackPressed = onBackPressed,
        headerContent = headerContent,
        state = state,
        cameraPreview = cameraPreview,
        onRegenerate = { viewModel.regenerateOffer(origin) },
        onStopStreaming = { viewModel.stopVideoStreaming() },
        onRetry = { viewModel.regenerateOffer(origin) },
        onMinimise = onMinimise,
        streamHostUiMode = streamHostUiMode,
        cameraPreviewController = resolvedCameraPreviewController,
        paymentCurrencyLabel = paymentCurrencyLabel,
        networkLabel = currentNetworkLabel,
        connectionType = connectionType,
        balanceAlgos = balanceAlgos,
        currentBlockNumber = currentBlockNumber,
        blockChainLabel = blockChainLabel,
        balanceCurrencySymbol = balanceCurrencySymbol,
        originUrl = origin,
        onStatsModalVisibilityChanged = { isAnalyticsModalVisible.value = it },
    )

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiquidAuthOfferScreenContent(
    showTopBar: Boolean,
    title: @Composable () -> Unit,
    onBackPressed: (() -> Unit)?,
    headerContent: @Composable (() -> Unit)? = null,
    state: LiquidAuthOfferViewModel.OfferState,
    cameraPreview: @Composable (() -> Unit)?,
    onRegenerate: () -> Unit,
    onStopStreaming: () -> Unit,
    onRetry: () -> Unit,
    onMinimise: () -> Unit,
    streamHostUiMode: MutableState<StreamHostUiMode>,
    cameraPreviewController: CameraStreamingPreviewController,
    paymentCurrencyLabel: String = "ALGO",
    networkLabel: String = "TESTNET",
    connectionType: IceConnectionType,
    balanceAlgos: Double?,
    currentBlockNumber: Long?,
    blockChainLabel: String = "Algorand",
    balanceCurrencySymbol: String = "A",
    originUrl: String = "-",
    onStatsModalVisibilityChanged: (Boolean) -> Unit = {},
) {

    Scaffold(
        topBar = {
            if (showTopBar) {
                TopAppBar(
                    title = title,
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = AlgoKitTheme.colors.background,
                            titleContentColor = AlgoKitTheme.colors.textMain,
                        ),
                    navigationIcon = {
                        onBackPressed?.let {
                            // Could add back button here
                        }
                    },
                )
            }
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(AlgoKitTheme.colors.background)
                    .padding(padding)
                    .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Top),
        ) {
            headerContent?.invoke()

            // Connection Status Card
            ConnectionStatusCard(
                state = state,
                paymentCurrencyLabel = paymentCurrencyLabel,
            )

            // Main Content Area - changes based on state
            val currentState = state
            when (currentState) {
                is LiquidAuthOfferViewModel.OfferState.WaitingForConnection -> {
                    QRCodeSection(
                        liquidAuthUrl = currentState.liquidAuthUrl,
                        requestId = currentState.requestId,
                        onRegenerate = onRegenerate,
                    )
                }

                /*   is LiquidAuthOfferViewModel.OfferState.Connected -> {
                       ConnectedSection(
                           sessionId = currentState.sessionId,
                           connectionType = connectionType,
                           balanceAlgos = balanceAlgos,
                           onStartCamera = onStartCamera,
                           onDisconnect = onDisconnect,
                           onRequestPayment = onRequestPayment,
                           showStartButton = true,
                           paymentCurrencyLabel = paymentCurrencyLabel,
                           streamHostUiMode = streamHostUiMode

                       )
                   }*/

                /*    is LiquidAuthOfferViewModel.OfferState.WaitingForPayment -> {
                        WaitingForPaymentSection(
                            sessionId = currentState.sessionId,
                            connectionType = connectionType,
                            balanceAlgos = balanceAlgos,
                            paymentRequest = currentState.paymentRequest,
                            onDisconnect = onDisconnect,
                            paymentCurrencyLabel = paymentCurrencyLabel,
                        )
                    }*/

                is LiquidAuthOfferViewModel.OfferState.Streaming,
                is LiquidAuthOfferViewModel.OfferState.WaitingForPayment,
                is LiquidAuthOfferViewModel.OfferState.Connected -> {
                    // Stream host UI is controlled below so it dismisses only on stop.
                    if (streamHostUiMode.value == StreamHostUiMode.Hidden) {
                        streamHostUiMode.value = StreamHostUiMode.Expanded
                    }
                }

                is LiquidAuthOfferViewModel.OfferState.Error -> {
                    ErrorSection(
                        message = currentState.message,
                        onRetry = onRetry,
                    )
                }

                else -> {
                    // Idle or Loading
                    LoadingSection()
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (streamHostUiMode.value == StreamHostUiMode.Expanded) {
        val sessionIdForStats =
            when (state) {
                is LiquidAuthOfferViewModel.OfferState.Connected -> state.sessionId
                is LiquidAuthOfferViewModel.OfferState.WaitingForPayment -> state.sessionId
                is LiquidAuthOfferViewModel.OfferState.Streaming -> state.sessionId
                else -> null
            }

        StreamHostBottomSheet(
            cameraPreview = cameraPreview,
            cameraPreviewController = cameraPreviewController,
            onStatsClick = {},
            onMinimise = {
                streamHostUiMode.value = StreamHostUiMode.Minimized
                onMinimise()
            },
            onDismiss = {
                streamHostUiMode.value = StreamHostUiMode.Minimized
                onMinimise()
            },
            sessionId = sessionIdForStats,
            balanceAlgos = balanceAlgos,
            connectionType = connectionType,
            currentBlockNumber = currentBlockNumber,
            networkLabel = networkLabel,
            blockChainLabel = blockChainLabel,
            balanceCurrencySymbol = balanceCurrencySymbol,
            originUrl = originUrl,
            onStatsModalVisibilityChanged = onStatsModalVisibilityChanged,
        )
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StreamHostBottomSheet(
    cameraPreview: @Composable (() -> Unit)?,
    cameraPreviewController: CameraStreamingPreviewController,
    onStatsClick: () -> Unit,
    onMinimise: () -> Unit,
    onDismiss: () -> Unit,
    sessionId: String?,
    balanceAlgos: Double?,
    connectionType: IceConnectionType,
    currentBlockNumber: Long?,
    networkLabel: String,
    blockChainLabel: String,
    balanceCurrencySymbol: String,
    originUrl: String,
    onStatsModalVisibilityChanged: (Boolean) -> Unit,
) {
    val isPreview = LocalInspectionMode.current
    if (isPreview) {
        // Avoid modal container in Compose Preview; render content directly for faster preview.
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(700.dp),
        ) {
            LiquidStreamHostLiveScreen(
                cameraPreview = cameraPreview,
                onSettingsClick = {},
                onMinimise = onMinimise,
                onRotateCamera = { cameraPreviewController.rotateCamera() },
                onStatsClick = onStatsClick,
                onStatsModalVisibilityChanged = onStatsModalVisibilityChanged,
                sessionId = sessionId,
                balanceAlgos = balanceAlgos,
                connectionType = connectionType,
                currentBlockNumber = currentBlockNumber,
                blockChainLabel = blockChainLabel,
                networkLabel = networkLabel,
                balanceCurrencySymbol = balanceCurrencySymbol,
                originUrl = originUrl,
            )
        }
        return
    }

    val streamHostBottomSheetState =
        rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
        )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = streamHostBottomSheetState,
        dragHandle = null,
        shape = RectangleShape,
        containerColor = Color.Transparent,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
        ) { innerPadding ->
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
            ) {
                LiquidStreamHostLiveScreen(
                    cameraPreview = cameraPreview,
                    onSettingsClick = {},
                    onMinimise = onMinimise,
                    onRotateCamera = { cameraPreviewController.rotateCamera() },
                    onStatsClick = {},
                    onStatsModalVisibilityChanged = onStatsModalVisibilityChanged,
                    sessionId = sessionId,
                    balanceAlgos = balanceAlgos,
                    connectionType = connectionType,
                    currentBlockNumber = currentBlockNumber,
                    blockChainLabel = blockChainLabel,
                    networkLabel = networkLabel,
                    balanceCurrencySymbol = balanceCurrencySymbol,
                    originUrl = originUrl,
                )
            }
        }
    }
}

@Composable
private fun ConnectionStatusCard(
    state: LiquidAuthOfferViewModel.OfferState,
    paymentCurrencyLabel: String = "ALGO",
) {
    val (statusText, statusColor) =
        when (state) {
            is LiquidAuthOfferViewModel.OfferState.Idle,
            is LiquidAuthOfferViewModel.OfferState.Loading,
                -> "Initializing..." to TextGray

            is LiquidAuthOfferViewModel.OfferState.WaitingForConnection ->
                "Waiting for client to scan QR code..." to PendingYellow

            is LiquidAuthOfferViewModel.OfferState.Connected ->
                "Client connected! Ready to stream" to SuccessGreen

            is LiquidAuthOfferViewModel.OfferState.WaitingForPayment ->
                "Waiting for 1 $paymentCurrencyLabel deposit..." to PendingYellow

            is LiquidAuthOfferViewModel.OfferState.Streaming ->
                "Streaming video to client" to SuccessGreen

            is LiquidAuthOfferViewModel.OfferState.Error ->
                "Error occurred" to MaterialTheme.colorScheme.error
        }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = AlgoKitTheme.colors.layerGray,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Status:",
                style = MaterialTheme.typography.labelMedium,
                color = AlgoKitTheme.colors.textGray,
            )
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = statusColor,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun QRCodeSection(
    liquidAuthUrl: String,
    requestId: String,
    onRegenerate: () -> Unit,
) {
    val qrPainter = rememberQrKitPainter(data = liquidAuthUrl)

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = AlgoKitTheme.colors.layerGray,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // QR Code
            Box(
                modifier =
                    Modifier
                        .size(250.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = qrPainter,
                    contentDescription = "Liquid Auth QR Code",
                    modifier = Modifier.size(220.dp),
                )
            }

            // Request ID
            Text(
                text = "Request ID:",
                style = MaterialTheme.typography.labelMedium,
                color = AlgoKitTheme.colors.textGray,
            )
            Text(
                text = requestId,
                style = MaterialTheme.typography.bodySmall,
                color = AlgoKitTheme.colors.textMain,
                textAlign = TextAlign.Center,
            )

            // Scan instruction
            Text(
                text = "Scan with another device to connect",
                style = MaterialTheme.typography.bodyMedium,
                color = AlgoKitTheme.colors.textGray,
                textAlign = TextAlign.Center,
            )

            // Regenerate button
            Button(
                onClick = onRegenerate,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Generate New QR Code")
            }
        }
    }
}

@Composable
private fun ConnectedSection(
    sessionId: String,
    connectionType: IceConnectionType,
    balanceAlgos: Double?,
    onStartCamera: () -> Unit,
    onDisconnect: () -> Unit,
    onRequestPayment: () -> Unit = {},
    showStartButton: Boolean = true,
    paymentCurrencyLabel: String = "ALGO",
    streamHostUiMode: MutableState<StreamHostUiMode>
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = AlgoKitTheme.colors.layerGray,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Success indicator
            Box(
                modifier =
                    Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(40.dp))
                        .background(SuccessGreen.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "✓",
                    style = MaterialTheme.typography.headlineLarge,
                    color = SuccessGreen,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Session info
            Text(
                text = "Client Connected!",
                style = MaterialTheme.typography.headlineSmall,
                color = AlgoKitTheme.colors.textMain,
            )

            Text(
                text = "Session: ${sessionId.take(8)}...",
                style = MaterialTheme.typography.bodyMedium,
                color = AlgoKitTheme.colors.textGray,
            )

            // Connection Type Indicator (for quality/billing visibility)
            ConnectionTypeIndicator(
                connectionType = connectionType,
                balanceAlgos = balanceAlgos,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Start camera button (only shown when showStartButton is true)
            if (showStartButton) {
                // Check if balance is depleted (0 or null means needs payment)
                val isDepleted = balanceAlgos == null || balanceAlgos <= 0.0

                if (isDepleted) {
                    streamHostUiMode.value = StreamHostUiMode.Hidden
                    // Show pay to resume button
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors =
                            CardDefaults.cardColors(
                                containerColor = Color(0xFFF44336).copy(alpha = 0.1f),
                            ),
                        border =
                            androidx.compose.foundation.BorderStroke(
                                1.dp,
                                Color(0xFFF44336),
                            ),
                    ) {
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = "⛽ Funds Depleted",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color(0xFFF44336),
                                fontWeight = FontWeight.Bold,
                            )

                            Text(
                                text =
                                    "The viewer has used all their deposit. " +
                                            "They need to pay 1 $paymentCurrencyLabel again to resume streaming.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AlgoKitTheme.colors.textGray,
                                textAlign = TextAlign.Center,
                            )

                            Button(
                                onClick = onRequestPayment,
                                modifier = Modifier.fillMaxWidth(),
                                colors =
                                    ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFFF44336),
                                    ),
                            ) {
                                Text("Request 1 $paymentCurrencyLabel Payment")
                            }
                        }
                    }
                } else {
                    // Normal start streaming button
                    Button(
                        onClick = onStartCamera,
                        modifier = Modifier.fillMaxWidth(),
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = PrimaryPurple,
                            ),
                    ) {
                        Text("Open Camera & Start Streaming")
                    }

                    Text(
                        text = "Stream live video back to the connected device",
                        style = MaterialTheme.typography.bodySmall,
                        color = AlgoKitTheme.colors.textGray,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Disconnect button
            Button(
                onClick = onDisconnect,
                modifier = Modifier.fillMaxWidth(),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
            ) {
                Text("Disconnect")
            }

            Text(
                text = "Force client to reconnect",
                style = MaterialTheme.typography.bodySmall,
                color = AlgoKitTheme.colors.textGray,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun WaitingForPaymentSection(
    sessionId: String,
    connectionType: IceConnectionType,
    balanceAlgos: Double?,
    paymentRequest: X402PaymentMessages.PaymentRequest,
    onDisconnect: () -> Unit,
    paymentCurrencyLabel: String = "ALGO",
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = AlgoKitTheme.colors.layerGray,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Payment indicator
            Box(
                modifier =
                    Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(40.dp))
                        .background(PendingYellow.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "💰",
                    style = MaterialTheme.typography.headlineLarge,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Session info
            Text(
                text = "Client Connected!",
                style = MaterialTheme.typography.headlineSmall,
                color = AlgoKitTheme.colors.textMain,
            )

            Text(
                text = "Session: ${sessionId.take(8)}...",
                style = MaterialTheme.typography.bodyMedium,
                color = AlgoKitTheme.colors.textGray,
            )

            // Connection Type Indicator
            ConnectionTypeIndicator(connectionType = connectionType)

            Spacer(modifier = Modifier.height(8.dp))

            // Payment request card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors =
                    CardDefaults.cardColors(
                        containerColor = AlgoKitTheme.colors.background,
                    ),
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Waiting for Payment",
                        style = MaterialTheme.typography.titleMedium,
                        color = AlgoKitTheme.colors.textMain,
                        fontWeight = FontWeight.Bold,
                    )

                    Text(
                        text = "1 $paymentCurrencyLabel deposit required",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AlgoKitTheme.colors.textGray,
                    )

                    Text(
                        text = "To: ${paymentRequest.creatorAddress.take(8)}...",
                        style = MaterialTheme.typography.bodySmall,
                        color = AlgoKitTheme.colors.textGray,
                    )

                    CircularProgressIndicator(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = PendingYellow,
                    )

                    Text(
                        text = "Waiting for client to sign transaction...",
                        style = MaterialTheme.typography.bodySmall,
                        color = AlgoKitTheme.colors.textGray,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Disconnect button
            Button(
                onClick = onDisconnect,
                modifier = Modifier.fillMaxWidth(),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
            ) {
                Text("Disconnect")
            }

            Text(
                text = "Cancel and force client to reconnect",
                style = MaterialTheme.typography.bodySmall,
                color = AlgoKitTheme.colors.textGray,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Suppress("unused")
@Composable
private fun StreamingSection(
    sessionId: String,
    connectionType: IceConnectionType,
    onStopStreaming: () -> Unit,
    onDisconnect: () -> Unit,
    cameraPreview: @Composable (() -> Unit)?,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = AlgoKitTheme.colors.layerGray,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(12.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Red),
                )
                Text(
                    text = "LIVE",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.Red,
                )
            }

            ConnectionTypeIndicator(connectionType = connectionType)

            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                if (cameraPreview != null) {
                    cameraPreview()
                } else {
                    Text(
                        text = "Camera Preview\n(Implement cameraPreview slot)",
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Text(
                text = "Streaming to: ${sessionId.take(8)}...",
                style = MaterialTheme.typography.bodyMedium,
                color = AlgoKitTheme.colors.textGray,
            )

            Button(
                onClick = onStopStreaming,
                modifier = Modifier.fillMaxWidth(),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
            ) {
                Text("Stop Streaming")
            }

            Button(
                onClick = onDisconnect,
                modifier = Modifier.fillMaxWidth(),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = AlgoKitTheme.colors.textGray,
                    ),
            ) {
                Text("Disconnect Client")
            }

            Text(
                text = "Force client to reconnect",
                style = MaterialTheme.typography.bodySmall,
                color = AlgoKitTheme.colors.textGray,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ErrorSection(
    message: String,
    onRetry: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = AlgoKitTheme.colors.layerGray,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Error",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.error,
            )

            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = AlgoKitTheme.colors.textMain,
                textAlign = TextAlign.Center,
            )

            Button(
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Try Again")
            }
        }
    }
}

@Composable
private fun LoadingSection() {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CircularProgressIndicator()
        Text(
            text = "Waiting...",
            style = MaterialTheme.typography.bodyMedium,
            color = AlgoKitTheme.colors.textGray,
        )
    }
}

@Composable
private fun ConnectionTypeIndicator(
    connectionType: IceConnectionType,
    balanceAlgos: Double? = null,
) {
    val isDetecting = connectionType == IceConnectionType.UNKNOWN

    val backgroundColor =
        if (isDetecting) {
            Color.Gray
        } else {
            hexToColor(connectionType.colorHex()) ?: AlgoKitTheme.colors.textGray
        }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = AlgoKitTheme.colors.layerGray.copy(alpha = 0.7f),
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Left: Connection type badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Color indicator dot (animated pulse when detecting)
                Box(
                    modifier =
                        Modifier
                            .size(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(backgroundColor),
                )

                Text(
                    text = if (isDetecting) "Detecting..." else connectionType.displayName(),
                    style = MaterialTheme.typography.labelMedium,
                    color = AlgoKitTheme.colors.textMain,
                    fontWeight = FontWeight.Medium,
                )
            }

            // Right: Balance or Cost tier
            Column(
                horizontalAlignment = Alignment.End,
            ) {
                // Show balance if available, otherwise show cost tier
                val displayText =
                    balanceAlgos?.let {
                        // Format to 1 decimal place KMP-compatible
                        val rounded = (kotlin.math.round(it * 10) / 10)
                        val text =
                            if (rounded == rounded.toInt().toDouble()) {
                                "${rounded.toInt()}A"
                            } else {
                                rounded.toString().take(3) + "A"
                            }
                        text
                    } ?: connectionType.costTier()

                Text(
                    text = displayText,
                    style = MaterialTheme.typography.titleMedium,
                    color =
                        when {
                            balanceAlgos != null -> SuccessGreen
                            isDetecting -> AlgoKitTheme.colors.textGray
                            connectionType == IceConnectionType.RELAY -> Color(0xFFFF9800)
                            else -> Color(0xFF4CAF50)
                        },
                    fontWeight = FontWeight.Bold,
                )

                // Latency info
                Text(
                    text = if (isDetecting) "..." else "~${connectionType.typicalLatency()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AlgoKitTheme.colors.textGray,
                )
            }
        }
    }

    // Help text explaining the cost (only when relay is detected and no balance shown)
    if (connectionType == IceConnectionType.RELAY && balanceAlgos == null) {
        Text(
            text = "⚠️ TURN relay active - higher bandwidth cost",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFFF9800),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
    }
}

@Preview
@Composable
private fun LiquidAuthOfferWaitingForConnectionPreview() {
    AlgoKitTheme {
        LiquidAuthOfferScreenContent(
            showTopBar = true,
            title = { Text("Liquid Auth") },
            onBackPressed = {},
            state =
                LiquidAuthOfferViewModel.OfferState.WaitingForConnection(
                    requestId = "abc123-request-id",
                    liquidAuthUrl = "https://auth.example.com/connect?requestId=abc123",
                    origin = "https://auth.example.com",
                ),
            cameraPreview = null,
            onRegenerate = {},
            onStopStreaming = {},
            onRetry = {},
            onMinimise = {},
            streamHostUiMode = mutableStateOf(StreamHostUiMode.Hidden),
            cameraPreviewController = remember { CameraStreamingPreviewController() },
            connectionType = IceConnectionType.UNKNOWN,
            balanceAlgos = null,
            currentBlockNumber = null,
        )
    }
}

@Preview
@Composable
private fun LiquidAuthOfferStreamingDirectPreview() {
    AlgoKitTheme {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(AlgoKitTheme.colors.background)
                    .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            LiquidStreamHostLiveScreen(
                cameraPreview = {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .background(Color(0xFF1E1E1E)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Direct camera stream",
                            color = Color.White,
                        )
                    }
                },
            )
            ConnectedViewersCard(
                sessionId = "session-direct-12345678",
                balanceAlgos = 0.8,
                connectionType = IceConnectionType.LOCAL,
                currentBlockNumber = 45123456L,
                networkLabel = "TESTNET",
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Preview
@Composable
private fun LiquidAuthOfferStreamingRelayPreview() {
    AlgoKitTheme {
        LiquidAuthOfferScreenContent(
            showTopBar = true,
            title = { Text("Liquid Auth") },
            onBackPressed = {},
            state =
                LiquidAuthOfferViewModel.OfferState.Streaming(
                    requestId = "stream-relay-req",
                    liquidAuthUrl = "https://auth.example.com/connect?requestId=stream-relay-req",
                    origin = "https://auth.example.com",
                    sessionId = "session-relay-87654321",
                    isPaid = true,
                ),
            cameraPreview = null,
            onRegenerate = {},
            onStopStreaming = {},
            onRetry = {},
            onMinimise = {},
            streamHostUiMode = mutableStateOf(StreamHostUiMode.Hidden),
            cameraPreviewController = remember { CameraStreamingPreviewController() },
            connectionType = IceConnectionType.UNKNOWN,
            balanceAlgos = null,
            currentBlockNumber = null,
        )
    }
}

@Preview
@Composable
private fun LiquidAuthOfferConnectedFundedPreview() {
    AlgoKitTheme {
        LiquidAuthOfferScreenContent(
            showTopBar = true,
            title = { Text("Liquid Auth") },
            onBackPressed = {},
            state =
                LiquidAuthOfferViewModel.OfferState.Connected(
                    requestId = "connected-funded-req",
                    liquidAuthUrl = "https://auth.example.com/connect?requestId=connected-funded-req",
                    origin = "https://auth.example.com",
                    sessionId = "session-funded-44556677",
                ),
            cameraPreview = null,
            onRegenerate = {},
            onStopStreaming = {},
            onRetry = {},
            onMinimise = {},
            streamHostUiMode = mutableStateOf(StreamHostUiMode.Hidden),
            cameraPreviewController = remember { CameraStreamingPreviewController() },
            connectionType = IceConnectionType.UNKNOWN,
            balanceAlgos = null,
            currentBlockNumber = null,
        )
    }
}

@Preview
@Composable
private fun LiquidAuthOfferConnectedDepletedPreview() {
    AlgoKitTheme {
        LiquidAuthOfferScreenContent(
            showTopBar = true,
            title = { Text("Liquid Auth") },
            onBackPressed = {},
            state =
                LiquidAuthOfferViewModel.OfferState.Connected(
                    requestId = "connected-depleted-req",
                    liquidAuthUrl = "https://auth.example.com/connect?requestId=connected-depleted-req",
                    origin = "https://auth.example.com",
                    sessionId = "session-depleted-88990011",
                ),
            cameraPreview = null,
            onRegenerate = {},
            onStopStreaming = {},
            onRetry = {},
            onMinimise = {},
            streamHostUiMode = mutableStateOf(StreamHostUiMode.Hidden),
            cameraPreviewController = remember { CameraStreamingPreviewController() },
            connectionType = IceConnectionType.UNKNOWN,
            balanceAlgos = null,
            currentBlockNumber = null,
        )
    }
}

@Preview
@Composable
private fun LiquidAuthOfferWaitingForPaymentPreview() {
    AlgoKitTheme {
        LiquidAuthOfferScreenContent(
            showTopBar = true,
            title = { Text("Liquid Auth") },
            onBackPressed = {},
            state =
                LiquidAuthOfferViewModel.OfferState.WaitingForPayment(
                    requestId = "payment-wait-req",
                    liquidAuthUrl = "https://auth.example.com/connect?requestId=payment-wait-req",
                    origin = "https://auth.example.com",
                    sessionId = "session-payment-33221100",
                    paymentRequest =
                        X402PaymentMessages.PaymentRequest(
                            id = "payment-session-123",
                            amountMicroAlgos = 1_000_000L,
                            creatorAddress = "CREATORADDR1234567890ABCDEFGH",
                            network = "testnet",
                        ),
                ),
            cameraPreview = null,
            onRegenerate = {},
            onStopStreaming = {},
            onRetry = {},
            onMinimise = {},
            streamHostUiMode = mutableStateOf(StreamHostUiMode.Hidden),
            cameraPreviewController = remember { CameraStreamingPreviewController() },
            connectionType = IceConnectionType.UNKNOWN,
            balanceAlgos = null,
            currentBlockNumber = null,
        )
    }
}

@Preview
@Composable
private fun LiquidAuthOfferErrorPreview() {
    AlgoKitTheme {
        LiquidAuthOfferScreenContent(
            showTopBar = true,
            title = { Text("Liquid Auth") },
            onBackPressed = {},
            state =
                LiquidAuthOfferViewModel.OfferState.Error(
                    message = "Failed to generate offer. Please check your network and try again.",
                ),
            cameraPreview = null,
            onRegenerate = {},
            onStopStreaming = {},
            onRetry = {},
            onMinimise = {},
            streamHostUiMode = mutableStateOf(StreamHostUiMode.Hidden),
            cameraPreviewController = remember { CameraStreamingPreviewController() },
            connectionType = IceConnectionType.UNKNOWN,
            balanceAlgos = null,
            currentBlockNumber = null,
        )
    }
}

@Preview
@Composable
private fun LiquidAuthOfferConnectedViewersCardWithBlockPreview() {
    AlgoKitTheme {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(AlgoKitTheme.colors.background)
                    .padding(vertical = 16.dp),
        ) {
            ConnectedViewersCard(
                sessionId = "session-preview-12345678",
                balanceAlgos = 0.7,
                connectionType = IceConnectionType.STUN,
                currentBlockNumber = 45123501L,
                networkLabel = "TESTNET",
            )
        }
    }
}

@Preview
@Composable
private fun LiquidAuthOfferConnectedViewersCardWithoutBlockPreview() {
    AlgoKitTheme {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(AlgoKitTheme.colors.background)
                    .padding(vertical = 16.dp),
        ) {
            ConnectedViewersCard(
                sessionId = "session-preview-87654321",
                balanceAlgos = 0.7,
                connectionType = IceConnectionType.STUN,
                networkLabel = "TESTNET",
            )
        }
    }
}

// Helper colors
private val SuccessGreen = Color(0xFF4CAF50)
private val PendingYellow = Color(0xFFFFC107)
private val PrimaryPurple = Color(0xFF9966FF)
private val TextGray = Color(0xFF888888)

/**
 * Convert hex color string (e.g., "#4CAF50") to Compose Color
 */
private fun hexToColor(hex: String): Color? =
    try {
        val cleanHex = hex.removePrefix("#")
        val colorInt = cleanHex.toLong(16)
        when (cleanHex.length) {
            6 ->
                Color(
                    red = ((colorInt shr 16) and 0xFF) / 255f,
                    green = ((colorInt shr 8) and 0xFF) / 255f,
                    blue = (colorInt and 0xFF) / 255f,
                    alpha = 1f,
                )

            8 ->
                Color(
                    red = ((colorInt shr 16) and 0xFF) / 255f,
                    green = ((colorInt shr 8) and 0xFF) / 255f,
                    blue = (colorInt and 0xFF) / 255f,
                    alpha = ((colorInt shr 24) and 0xFF) / 255f,
                )

            else -> null
        }
    } catch (_: Exception) {
        null
    }
