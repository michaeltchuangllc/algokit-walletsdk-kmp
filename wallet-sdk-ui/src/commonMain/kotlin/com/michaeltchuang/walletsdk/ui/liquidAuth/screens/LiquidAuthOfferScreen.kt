package com.michaeltchuang.walletsdk.ui.liquidAuth.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.liquidAuth.model.IceConnectionType
import com.michaeltchuang.walletsdk.ui.liquidAuth.model.MppPaymentMessages
import com.michaeltchuang.walletsdk.ui.liquidAuth.model.colorHex
import com.michaeltchuang.walletsdk.ui.liquidAuth.model.costTier
import com.michaeltchuang.walletsdk.ui.liquidAuth.model.displayName
import com.michaeltchuang.walletsdk.ui.liquidAuth.model.typicalLatency
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.LiquidAuthOfferViewModel
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.koin.compose.viewmodel.koinViewModel
import qrgenerator.qrkitpainter.rememberQrKitPainter

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
    creatorAddress: String? = null, // For MPP paid streaming
    enablePaidStreaming: Boolean = false, // Toggle MPP payments
) {
    val viewModel: LiquidAuthOfferViewModel = koinViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val connectionType by viewModel.connectionType.collectAsStateWithLifecycle()
    val remainingBalanceMicroAlgos by viewModel.remainingBalanceMicroAlgos.collectAsStateWithLifecycle()
    val currentBlockNumber by viewModel.currentBlockNumber.collectAsStateWithLifecycle()

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

    // Start listening after initialization
    LaunchedEffect(state, connectionManager) {
        // Ensure connectionManager is initialized before starting
        if (connectionManager == null) {
            println("⚠️ Cannot start listening - connectionManager is null")
            return@LaunchedEffect
        }

        val currentState = state
        println("🔗 State changed to: ${currentState::class.simpleName}")

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
            }
            is LiquidAuthOfferViewModel.OfferState.WaitingForPayment -> {
                println("🔗 Waiting for payment - keeping connection open")
            }
            else -> {
                println("🔗 Stopping listening - state: ${currentState::class.simpleName}")
                connectionManager.stopListening()
            }
        }
    }

    // Handle MPP PaymentRequested event - only depend on viewModel to avoid restarts
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
                    println("💰 PaymentRequested event received, sending via connectionManager...")
                    currentConnectionManager?.sendPaymentRequest(event.paymentRequest)
                    println("💰 Payment request sent: ${event.paymentRequest.amountMicroAlgos} microAlgos")
                }
                is LiquidAuthOfferViewModel.OfferEvent.PaymentReceived -> {
                    // Start block consumption when payment received
                    println("💰 PaymentReceived event received!")
                    val sessionId = viewModel.getCurrentSessionId()
                    if (sessionId != null) {
                        currentConnectionManager?.startBlockConsumption(sessionId)
                        println("💰 Payment received! Starting block consumption")
                    }
                }
                is LiquidAuthOfferViewModel.OfferEvent.FundsDepleted -> {
                    // Stop everything when funds depleted
                    currentConnectionManager?.stopBlockConsumption()
                    viewModel.stopVideoStreaming() // Stop the video feed
                    println("💰 Funds depleted! Stopping stream and block consumption")
                    println("💰 Viewer must pay again to resume streaming")
                }
                else -> { /* other events */ }
            }
        }
    }

    // Cleanup
    DisposableEffect(Unit) {
        onDispose {
            connectionManager?.stopListening()
            connectionManager?.stopBlockConsumption()
        }
    }

    LiquidAuthOfferScreenContent(
        showTopBar = showTopBar,
        title = title,
        onBackPressed = onBackPressed,
        state = state,
        connectionType = connectionType,
        balanceAlgos = balanceAlgos,
        currentBlockNumber = currentBlockNumber,
        cameraPreview = cameraPreview,
        onRegenerate = { viewModel.regenerateOffer(origin) },
        onStartCamera = { viewModel.startVideoStreaming() },
        onDisconnect = { connectionManager?.stopListening() },
        onRequestPayment = {
            currentCreatorAddress?.let { address ->
                println("💰 Requesting additional payment from viewer...")
                viewModel.requestPaymentFromClient(address)
            }
        },
        onStopStreaming = { viewModel.stopVideoStreaming() },
        onRetry = { viewModel.regenerateOffer(origin) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LiquidAuthOfferScreenContent(
    showTopBar: Boolean,
    title: @Composable () -> Unit,
    onBackPressed: (() -> Unit)?,
    state: LiquidAuthOfferViewModel.OfferState,
    connectionType: IceConnectionType,
    balanceAlgos: Double?,
    currentBlockNumber: Long?,
    cameraPreview: @Composable (() -> Unit)?,
    onRegenerate: () -> Unit,
    onStartCamera: () -> Unit,
    onDisconnect: () -> Unit,
    onRequestPayment: () -> Unit,
    onStopStreaming: () -> Unit,
    onRetry: () -> Unit,
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
            Spacer(modifier = Modifier.height(16.dp))

            // Connection Status Card
            ConnectionStatusCard(state = state)

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

                is LiquidAuthOfferViewModel.OfferState.Connected -> {
                    ConnectedSection(
                        sessionId = currentState.sessionId,
                        connectionType = connectionType,
                        balanceAlgos = balanceAlgos,
                        onStartCamera = onStartCamera,
                        onDisconnect = onDisconnect,
                        onRequestPayment = onRequestPayment,
                        showStartButton = true,
                    )
                }

                is LiquidAuthOfferViewModel.OfferState.WaitingForPayment -> {
                    WaitingForPaymentSection(
                        sessionId = currentState.sessionId,
                        connectionType = connectionType,
                        balanceAlgos = balanceAlgos,
                        paymentRequest = currentState.paymentRequest,
                        onDisconnect = onDisconnect,
                    )
                }

                is LiquidAuthOfferViewModel.OfferState.Streaming -> {
                    StreamingSection(
                        sessionId = currentState.sessionId,
                        connectionType = connectionType,
                        onStopStreaming = onStopStreaming,
                        onDisconnect = onDisconnect,
                        cameraPreview = cameraPreview,
                    )

                    // Connected Viewers List (shows current viewer and their balance)
                    if (balanceAlgos != null) {
                        ConnectedViewersCard(
                            sessionId = currentState.sessionId,
                            balanceAlgos = balanceAlgos,
                            connectionType = connectionType,
                            currentBlockNumber = currentBlockNumber,
                        )
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
}

@Composable
private fun ConnectionStatusCard(state: LiquidAuthOfferViewModel.OfferState) {
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
                "Waiting for 1 ALGO deposit..." to PendingYellow

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
                                text = "The viewer has used all their deposit. They need to pay 1 ALGO again to resume streaming.",
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
                                Text("Request 1 ALGO Payment")
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
    paymentRequest: MppPaymentMessages.PaymentRequest,
    onDisconnect: () -> Unit,
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
                        text = "1 ALGO deposit required",
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
            // Live indicator
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

            // Connection Type Indicator (shows cost tier for MPP billing)
            ConnectionTypeIndicator(connectionType = connectionType)

            // Camera preview slot
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

            // Session info
            Text(
                text = "Streaming to: ${sessionId.take(8)}...",
                style = MaterialTheme.typography.bodyMedium,
                color = AlgoKitTheme.colors.textGray,
            )

            // Stop streaming button
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

            // Disconnect button (secondary action)
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
            text = "Initializing...",
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
            connectionType = IceConnectionType.UNKNOWN,
            balanceAlgos = null,
            currentBlockNumber = null,
            cameraPreview = null,
            onRegenerate = {},
            onStartCamera = {},
            onDisconnect = {},
            onRequestPayment = {},
            onStopStreaming = {},
            onRetry = {},
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
            StreamingSection(
                sessionId = "session-direct-12345678",
                connectionType = IceConnectionType.LOCAL,
                onStopStreaming = {},
                onDisconnect = {},
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
            connectionType = IceConnectionType.RELAY,
            balanceAlgos = 0.2,
            currentBlockNumber = 45123459L,
            cameraPreview = null,
            onRegenerate = {},
            onStartCamera = {},
            onDisconnect = {},
            onRequestPayment = {},
            onStopStreaming = {},
            onRetry = {},
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
            connectionType = IceConnectionType.STUN,
            balanceAlgos = 1.0,
            currentBlockNumber = null,
            cameraPreview = null,
            onRegenerate = {},
            onStartCamera = {},
            onDisconnect = {},
            onRequestPayment = {},
            onStopStreaming = {},
            onRetry = {},
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
            connectionType = IceConnectionType.RELAY,
            balanceAlgos = 0.0,
            currentBlockNumber = null,
            cameraPreview = null,
            onRegenerate = {},
            onStartCamera = {},
            onDisconnect = {},
            onRequestPayment = {},
            onStopStreaming = {},
            onRetry = {},
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
                        MppPaymentMessages.PaymentRequest(
                            id = "payment-session-123",
                            amountMicroAlgos = 1_000_000L,
                            creatorAddress = "CREATORADDR1234567890ABCDEFGH",
                            network = "testnet",
                        ),
                ),
            connectionType = IceConnectionType.STUN,
            balanceAlgos = null,
            currentBlockNumber = null,
            cameraPreview = null,
            onRegenerate = {},
            onStartCamera = {},
            onDisconnect = {},
            onRequestPayment = {},
            onStopStreaming = {},
            onRetry = {},
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
            connectionType = IceConnectionType.UNKNOWN,
            balanceAlgos = null,
            currentBlockNumber = null,
            cameraPreview = null,
            onRegenerate = {},
            onStartCamera = {},
            onDisconnect = {},
            onRequestPayment = {},
            onStopStreaming = {},
            onRetry = {},
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
                balanceAlgos = 0.2,
                connectionType = IceConnectionType.RELAY,
                currentBlockNumber = null,
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
