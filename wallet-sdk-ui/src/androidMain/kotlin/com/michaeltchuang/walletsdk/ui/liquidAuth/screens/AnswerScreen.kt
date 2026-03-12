package com.michaeltchuang.walletsdk.ui.liquidAuth.screens

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
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * Answer Screen for Liquid Auth Client
 *
 * Main purpose: Sign transactions from connected dApp.
 * Optional feature: View broadcaster's camera feed in a compact overlay.
 */
@Composable
fun AnswerScreen(viewModel: AnswerViewModel) {
    val session by viewModel.session.collectAsState()
    val message by viewModel.authMessage.collectAsState()
    val accountAddress by viewModel.accountAddress.collectAsState()
    val errorMessage by viewModel.error.collectAsState()
    val videoFrame by viewModel.videoFrame.collectAsState()
    val isStreamActive by viewModel.isStreamActive.collectAsState()

    val isWaiting = message == null && errorMessage == null
    val hasError = errorMessage != null
    val isConnected = message != null && session != "Logged Out" && !hasError
    val isConnecting = message != null && session == "Logged Out" && !hasError

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
    )
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
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        // Main content - Transaction signing is the primary focus
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
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
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
    }
}

/**
 * Compact floating video preview - doesn't interfere with main UI
 */
@Composable
private fun CompactVideoPreview(
    videoFrame: AnswerViewModel.VideoFrameData,
    isLive: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .padding(8.dp)
            .size(160.dp, 120.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.Black,
        ),
        elevation = CardDefaults.cardElevation(8.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
        ) {
            // Video frame
            VideoFrameDisplay(
                frameData = videoFrame.data,
                aspectRatio = videoFrame.width.toFloat() / videoFrame.height.toFloat(),
            )

            // Close button overlay
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(32.dp)
                    .padding(4.dp),
            ) {
                Text(
                    text = "✕",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            // Live/Ended indicator
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .background(
                        if (isLive) Color.Red else Color.Gray,
                        RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isLive) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color.White),
                    )
                    Spacer(modifier = Modifier.size(4.dp))
                }
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

/**
 * Indicator shown when video is available but hidden
 */
@Composable
private fun VideoAvailableIndicator(
    onShowVideo: () -> Unit,
) {
    Card(
        onClick = onShowVideo,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = AlgoKitTheme.colors.layerGray,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
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
                text = "Show ▶",
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = AlgoKitTheme.colors.layerGray,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Gray),
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = "Stream ended - broadcaster stopped",
                style = MaterialTheme.typography.bodyMedium,
                color = AlgoKitTheme.colors.textGray,
            )
        }
    }
}

/**
 * Transaction signing area - main interaction point
 */
@Composable
private fun TransactionSigningArea(
    onSignClick: () -> Unit,
    isReady: Boolean,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
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
                modifier = Modifier
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
