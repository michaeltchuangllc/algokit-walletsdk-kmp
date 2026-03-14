package com.michaeltchuang.walletsdk.ui.liquidAuth.screens

import android.util.Log
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.michaeltchuang.walletsdk.ui.R
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.liquidAuth.AnswerViewModel
import com.michaeltchuang.walletsdk.ui.liquidAuth.components.VideoFrameDisplay
import com.michaeltchuang.walletsdk.ui.liquidAuth.model.X402PaymentMessages
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * Answer Screen for Liquid Auth Client
 *
 * Main purpose: Sign transactions from connected dApp.
 * Optional feature: View broadcaster's camera feed in a compact overlay.
 * X402 Payment: Pay to watch streaming content.
 */
@Composable
fun AnswerScreen(viewModel: AnswerViewModel) {
    val session by viewModel.session.collectAsState()
    val message by viewModel.authMessage.collectAsState()
    val accountAddress by viewModel.accountAddress.collectAsState()
    val errorMessage by viewModel.error.collectAsState()
    val videoFrame by viewModel.videoFrame.collectAsState()
    val isStreamActive by viewModel.isStreamActive.collectAsState()

    // X402 Payment dialog state
    var showPaymentDialog by remember { mutableStateOf(false) }
    var pendingPaymentRequest by remember { mutableStateOf<X402PaymentMessages.PaymentRequest?>(null) }
    var paymentBalance by remember { mutableStateOf<String?>(null) }
    var fundsDepleted by remember { mutableStateOf(false) }

    // Listen for X402 payment events
    val scope = rememberCoroutineScope()
    LaunchedEffect(viewModel) {
        Log.d("AnswerScreen", "🎭 Starting to collect view events...")
        viewModel.viewEvent.collect { event ->
            Log.d("AnswerScreen", "🎭 View event received: ${event::class.simpleName}")
            when (event) {
                is AnswerViewModel.ViewEvent.PaymentRequested -> {
                    Log.d("AnswerScreen", "🎭 PaymentRequested - showing dialog for ${event.paymentRequest.amountMicroAlgos} microAlgos")
                    pendingPaymentRequest = event.paymentRequest
                    showPaymentDialog = true
                    fundsDepleted = false
                }
                is AnswerViewModel.ViewEvent.BalanceUpdated -> {
                    Log.d("AnswerScreen", "🎭 BalanceUpdated: ${event.balanceUpdate.remainingAlgos()} ALGO")
                    paymentBalance = event.balanceUpdate.remainingAlgos().toString()
                }
                is AnswerViewModel.ViewEvent.FundsDepleted -> {
                    Log.d("AnswerScreen", "🎭 FundsDepleted")
                    fundsDepleted = true
                    showPaymentDialog = false
                }
                else -> { /* other events */ }
            }
        }
    }

    val isWaiting = message == null && errorMessage == null
    val hasError = errorMessage != null
    val isConnected = message != null && session != "Logged Out" && !hasError
    val isConnecting = message != null && session == "Logged Out" && !hasError

    Box(modifier = Modifier.fillMaxSize()) {
        ScreenContentAnswer(
            isConnected = isConnected,
            isWaiting = isWaiting,
            isConnecting = isConnecting,
            hasError = hasError,
            errorMessage = errorMessage,
            session = session,
            origin = message?.origin,
            requestId = message?.requestId,
            accountAddress = accountAddress,
            videoFrame = videoFrame,
            isStreamActive = isStreamActive,
            paymentBalance = paymentBalance,
            fundsDepleted = fundsDepleted,
        )

        // X402 Payment Dialog Overlay
        if (showPaymentDialog && pendingPaymentRequest != null) {
            Log.d("AnswerScreen", "🎭 Showing X402PaymentDialog")
            X402PaymentDialog(
                paymentRequest = pendingPaymentRequest!!,
                onApprove = {
                    // Create and sign real transaction
                    scope.launch {
                        viewModel.createAndSendPayment(pendingPaymentRequest!!)
                        showPaymentDialog = false
                    }
                },
                onReject = {
                    viewModel.sendPaymentResponse(
                        pendingPaymentRequest!!,
                        X402PaymentMessages.PaymentResponse.Status.REJECTED,
                        null,
                    )
                    showPaymentDialog = false
                },
            )
        }
    }
}

@OptIn(ExperimentalResourceApi::class)
@Composable
fun ScreenContentAnswer(
    isConnected: Boolean,
    isWaiting: Boolean,
    isConnecting: Boolean,
    hasError: Boolean,
    errorMessage: String?,
    session: String,
    origin: String?,
    requestId: String?,
    accountAddress: String,
    videoFrame: AnswerViewModel.VideoFrameData? = null,
    isStreamActive: Boolean = false,
    paymentBalance: String? = null,
    fundsDepleted: Boolean = false,
) {
    // User can toggle video visibility
    var showVideo by remember { mutableStateOf(true) }
    // Has a frame to display (even if stream ended, show last frame)
    val hasVideoFrame = videoFrame != null
    // Stream is actively receiving new frames
    val isStreamActive = isStreamActive

    // Auto-close video when stream ends (like pressing X button)
    LaunchedEffect(isStreamActive) {
        if (!isStreamActive && hasVideoFrame) {
            // Give user 1 second to see "ENDED" badge, then close
            kotlinx.coroutines.delay(1000)
            showVideo = false
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
    ) {
        // Main content - Transaction signing is the primary focus
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
        ) {
            // Header
            Text(
                text = stringResource(R.string.liquid_auth_header),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )

            // Connection Status Card - Primary UI
            ConnectionStatusCard(
                isConnected = isConnected,
                isWaiting = isWaiting,
                isConnecting = isConnecting,
                hasError = hasError,
                errorMessage = errorMessage,
                session = session,
                origin = origin,
                requestId = requestId,
                accountAddress = accountAddress,
            )

            // X402 Payment Status (when paid streaming)
            if (paymentBalance != null) {
                PaymentStatusCard(
                    balance = paymentBalance,
                    fundsDepleted = fundsDepleted,
                )
            }

            // Transaction Signing Area (when connected)
            if (isConnected) {
                TransactionSigningArea(
                    onSignClick = { /* Launch signing flow */ },
                    isReady = true,
                )
            }

            // Account Info Card
            if (accountAddress.isNotEmpty()) {
                AccountInfoCard(accountAddress = accountAddress)
            }

            // Video stream indicator (when video is hidden but available)
            if (isStreamActive && !showVideo) {
                VideoAvailableIndicator(
                    onShowVideo = { showVideo = true },
                )
            }

            // Stream ended indicator (when video was showing but stream stopped)
            if (!isStreamActive && videoFrame != null) {
                StreamEndedIndicator()
            }
        }

        // Floating video preview (compact overlay in corner)
        if (hasVideoFrame && showVideo) {
            CompactVideoPreview(
                videoFrame = videoFrame!!,
                isLive = isStreamActive,
                onClose = { showVideo = false },
            )
        }
    }
}

/**
 * X402 Payment Status Card - Shows streaming balance
 */
@Composable
private fun PaymentStatusCard(
    balance: String,
    fundsDepleted: Boolean,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = if (fundsDepleted) Color(0xFFFFEBEE) else Color(0xFFE8F5E9),
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    text = if (fundsDepleted) "⛽ Funds Depleted" else "💰 Streaming Balance",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (fundsDepleted) Color(0xFFC62828) else Color(0xFF2E7D32),
                )
                Text(
                    text = if (fundsDepleted) "Stream stopped" else "$balance ALGO remaining",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AlgoKitTheme.colors.textGray,
                )
            }
            if (!fundsDepleted) {
                Box(
                    modifier =
                        Modifier
                            .size(12.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF4CAF50)),
                )
            }
        }
    }
}

/**
 * Connection Status Card
 */
@Composable
private fun ConnectionStatusCard(
    isConnected: Boolean,
    isWaiting: Boolean,
    isConnecting: Boolean,
    hasError: Boolean,
    errorMessage: String?,
    session: String,
    origin: String?,
    requestId: String?,
    accountAddress: String,
) {
    val (statusText, statusColor) =
        when {
            hasError -> "Error: $errorMessage" to MaterialTheme.colorScheme.error
            isConnected -> "Connected to $session" to Color(0xFF4CAF50)
            isConnecting -> "Connecting..." to Color(0xFFFFA000)
            isWaiting -> "Waiting for connection" to AlgoKitTheme.colors.textGray
            else -> "Unknown state" to AlgoKitTheme.colors.textGray
        }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = statusColor,
            )

            if (origin != null) {
                InfoRow(label = "Origin:", value = origin)
            }
            if (requestId != null) {
                InfoRow(label = "Request ID:", value = requestId)
            }
            if (accountAddress.isNotEmpty()) {
                InfoRow(label = "Account:", value = accountAddress.take(8) + "...")
            }
        }
    }
}

/**
 * Transaction Signing Area
 */
@Composable
private fun TransactionSigningArea(
    onSignClick: () -> Unit,
    isReady: Boolean,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
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
                text = "Ready to Sign",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = AlgoKitTheme.colors.textMain,
            )

            Text(
                text = "Transactions from connected dApp will appear here for your approval.",
                style = MaterialTheme.typography.bodyMedium,
                color = AlgoKitTheme.colors.textGray,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Placeholder for transaction details
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(AlgoKitTheme.colors.background)
                        .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Waiting for transaction request...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AlgoKitTheme.colors.textGray,
                )
            }
        }
    }
}

/**
 * Compact Video Preview - Floating overlay in corner
 */
@Composable
private fun CompactVideoPreview(
    videoFrame: AnswerViewModel.VideoFrameData,
    isLive: Boolean,
    onClose: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize(),
        contentAlignment = Alignment.BottomEnd,
    ) {
        Card(
            modifier =
                Modifier
                    .padding(16.dp)
                    .size(160.dp, 120.dp),
            shape = RoundedCornerShape(12.dp),
            colors =
                CardDefaults.cardColors(
                    containerColor = Color.Black,
                ),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Video frame display
                val aspectRatio = if (videoFrame.height > 0) videoFrame.width.toFloat() / videoFrame.height else 4f / 3f
                VideoFrameDisplay(
                    frameData = videoFrame.data,
                    aspectRatio = aspectRatio,
                )

                // Close button
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close video",
                        tint = Color.White,
                    )
                }

                // Live/Ended indicator
                Row(
                    modifier =
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isLive) Color.Red else Color.Gray)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(if (isLive) Color.White else Color.Transparent),
                    )
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(
                        text = if (isLive) "LIVE" else "ENDED",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

/**
 * Indicator shown when video is available but hidden
 */
@Composable
private fun VideoAvailableIndicator(onShowVideo: () -> Unit) {
    Card(
        onClick = onShowVideo,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = AlgoKitTheme.colors.layerGray,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(12.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Red),
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = "Camera feed available",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AlgoKitTheme.colors.textMain,
                )
            }
            Text(
                text = "Show ",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * Stream ended indicator
 */
@Composable
private fun StreamEndedIndicator() {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = Color(0xFFFFF3E0),
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Gray),
            )
            Text(
                text = "Stream ended - broadcaster stopped",
                style = MaterialTheme.typography.bodyMedium,
                color = AlgoKitTheme.colors.textMain,
            )
        }
    }
}

/**
 * X402 Payment Dialog - Pay to watch streaming content
 */
@Composable
private fun X402PaymentDialog(
    paymentRequest: X402PaymentMessages.PaymentRequest,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    var isProcessing by remember { mutableStateOf(false) }
    val amountAlgos = paymentRequest.amountMicroAlgos / 1_000_000.0

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier =
                Modifier
                    .padding(32.dp)
                    .fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors =
                CardDefaults.cardColors(
                    containerColor = AlgoKitTheme.colors.background,
                ),
        ) {
            Column(
                modifier =
                    Modifier
                        .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Header
                Text(
                    text = "",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = AlgoKitTheme.colors.textMain,
                )

                // Amount
                Text(
                    text = "$amountAlgos ALGO",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )

                // Description
                Text(
                    text = "Pay to watch live stream",
                    style = MaterialTheme.typography.bodyLarge,
                    color = AlgoKitTheme.colors.textMain,
                    textAlign = TextAlign.Center,
                )

                // Cost breakdown
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = AlgoKitTheme.colors.layerGray,
                        ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        InfoRow(label = "Deposit:", value = "1.0 ALGO")
                        InfoRow(label = "Cost per block:", value = "0.1 ALGO")
                        InfoRow(label = "Network:", value = paymentRequest.network)
                        InfoRow(label = "Creator:", value = paymentRequest.creatorAddress.take(8) + "...")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Button(
                        onClick = onReject,
                        modifier = Modifier.weight(1f),
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = Color.Gray,
                            ),
                        enabled = !isProcessing,
                    ) {
                        Text("Reject")
                    }

                    Button(
                        onClick = {
                            isProcessing = true
                            onApprove()
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !isProcessing,
                    ) {
                        if (isProcessing) {
                            Text("Signing...")
                        } else {
                            Text("Pay & Watch")
                        }
                    }
                }
            }
        }
    }
}
