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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.LiquidAuthOfferViewModel
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
) {
    val viewModel: LiquidAuthOfferViewModel = koinViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Auto-generate offer on first composition
    LaunchedEffect(origin) {
        viewModel.generateOffer(origin)
    }

    // Wire up connection manager to ViewModel
    LaunchedEffect(Unit) {
        connectionManager?.initialize(viewModel)
    }
    
    // Start/stop listening based on state
    LaunchedEffect(state) {
        val currentState = state
        when (currentState) {
            is LiquidAuthOfferViewModel.OfferState.WaitingForConnection -> {
                connectionManager?.startListening(
                    origin = currentState.origin,
                    requestId = currentState.requestId
                )
            }
            is LiquidAuthOfferViewModel.OfferState.Connected,
            is LiquidAuthOfferViewModel.OfferState.Streaming -> {
                // Connection established - service is handling it
            }
            else -> {
                // Idle, Loading, or Error - stop listening
                connectionManager?.stopListening()
            }
        }
    }
    
    // Cleanup
    DisposableEffect(Unit) {
        onDispose {
            connectionManager?.stopListening()
        }
    }

    Scaffold(
        topBar = {
            if (showTopBar) {
                TopAppBar(
                    title = title,
                    colors = TopAppBarDefaults.topAppBarColors(
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
            modifier = Modifier
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
                        onRegenerate = { viewModel.regenerateOffer(origin) },
                    )
                }

                is LiquidAuthOfferViewModel.OfferState.Connected -> {
                    ConnectedSection(
                        sessionId = currentState.sessionId,
                        onStartCamera = { viewModel.startVideoStreaming() },
                        onDisconnect = { connectionManager?.stopListening() },
                    )
                }

                is LiquidAuthOfferViewModel.OfferState.Streaming -> {
                    StreamingSection(
                        sessionId = currentState.sessionId,
                        onStopStreaming = { viewModel.stopVideoStreaming() },
                        onDisconnect = { connectionManager?.stopListening() },
                        cameraPreview = cameraPreview,
                    )
                }

                is LiquidAuthOfferViewModel.OfferState.Error -> {
                    ErrorSection(
                        message = currentState.message,
                        onRetry = { viewModel.regenerateOffer(origin) },
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
private fun ConnectionStatusCard(
    state: LiquidAuthOfferViewModel.OfferState,
) {
    val (statusText, statusColor) = when (state) {
        is LiquidAuthOfferViewModel.OfferState.Idle,
        is LiquidAuthOfferViewModel.OfferState.Loading,
        -> "Initializing..." to TextGray

        is LiquidAuthOfferViewModel.OfferState.WaitingForConnection ->
            "Waiting for client to scan QR code..." to PendingYellow

        is LiquidAuthOfferViewModel.OfferState.Connected ->
            "Client connected! Ready to stream" to SuccessGreen

        is LiquidAuthOfferViewModel.OfferState.Streaming ->
            "Streaming video to client" to SuccessGreen

        is LiquidAuthOfferViewModel.OfferState.Error ->
            "Error occurred" to MaterialTheme.colorScheme.error
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = AlgoKitTheme.colors.layerGray,
        ),
    ) {
        Column(
            modifier = Modifier
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = AlgoKitTheme.colors.layerGray,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // QR Code
            Box(
                modifier = Modifier
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
    onStartCamera: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = AlgoKitTheme.colors.layerGray,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Success indicator
            Box(
                modifier = Modifier
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

            Spacer(modifier = Modifier.height(16.dp))

            // Start camera button
            Button(
                onClick = onStartCamera,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
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
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Disconnect button
            Button(
                onClick = onDisconnect,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
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
private fun StreamingSection(
    sessionId: String,
    onStopStreaming: () -> Unit,
    onDisconnect: () -> Unit,
    cameraPreview: @Composable (() -> Unit)?,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = AlgoKitTheme.colors.layerGray,
        ),
    ) {
        Column(
            modifier = Modifier
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
                    modifier = Modifier
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

            // Camera preview slot
            Box(
                modifier = Modifier
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
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text("Stop Streaming")
            }

            // Disconnect button (secondary action)
            Button(
                onClick = onDisconnect,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = AlgoKitTheme.colors.layerGray,
        ),
    ) {
        Column(
            modifier = Modifier
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
        modifier = Modifier
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

// Helper colors
private val SuccessGreen = Color(0xFF4CAF50)
private val PendingYellow = Color(0xFFFFC107)
private val PrimaryPurple = Color(0xFF9966FF)
private val TextGray = Color(0xFF888888)
