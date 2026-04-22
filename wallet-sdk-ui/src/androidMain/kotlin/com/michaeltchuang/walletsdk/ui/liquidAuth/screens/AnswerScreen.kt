package com.michaeltchuang.walletsdk.ui.liquidAuth.screens

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.michaeltchuang.walletsdk.core.deeplink.utils.AssetConstants.USDC_TESTNET_ID
import com.michaeltchuang.walletsdk.core.railmpp.core.BudgetCap
import com.michaeltchuang.walletsdk.core.railmpp.core.ConsentApproval
import com.michaeltchuang.walletsdk.core.railmpp.utils.MppPayments
import com.michaeltchuang.walletsdk.ui.R
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.liquidAuth.AnswerViewModel
import com.michaeltchuang.walletsdk.ui.liquidAuth.components.LiquidAuthSessionVaultModal
import com.michaeltchuang.walletsdk.ui.liquidAuth.components.VideoFrameDisplay
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

/**
 * Answer Screen for Liquid Auth Client
 *
 * Main purpose: Sign transactions from connected dApp.
 * Optional feature: View broadcaster's camera feed in a compact overlay.
 * X402 Payment: Pay to watch streaming content.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnswerScreen(
    viewModel: AnswerViewModel,
    onMinimizeToPip: () -> Unit = {},
) {
    val session by viewModel.session.collectAsState()
    val message by viewModel.authMessage.collectAsState()
    val accountAddress by viewModel.accountAddress.collectAsState()
    val errorMessage by viewModel.error.collectAsState()
    val videoFrame by viewModel.videoFrame.collectAsState()
    val pendingMppConsentFromState by viewModel.pendingMppConsent.collectAsState()
    val viewerSessionVaultMicroUsdc by viewModel.viewerSessionVaultMicroUsdc.collectAsState()
    val viewerProgressBalanceMicroUsdc by viewModel.viewerProgressBalanceMicroUsdc.collectAsState()
    val currentBlockNumber by viewModel.currentBlockNumber.collectAsState()

    var isSolanaAccount by remember { mutableStateOf(false) }
    LaunchedEffect(accountAddress) {
        isSolanaAccount = accountAddress.isNotBlank() && viewModel.isSeedVaultAccount(accountAddress)
    }

    var showPaymentDialog by remember { mutableStateOf(false) }
    var isPaymentProcessing by remember { mutableStateOf(false) }
    var isViewerSheetVisible by rememberSaveable { mutableStateOf(true) }

    val scope = rememberCoroutineScope()
    LaunchedEffect(viewModel) {
        viewModel.startRealtimeBlockNumberUpdates()
        Log.d("AnswerScreen", "🎭 Starting to collect view events...")
        viewModel.viewEvent.collect { event ->
            Log.d("AnswerScreen", "🎭 View event received: ${event::class.simpleName}")
            when (event) {
                is AnswerViewModel.ViewEvent.StreamDisconnected -> {
                    Log.w("AnswerScreen", "Viewer stream disconnected: ${event.reason}")
                    isViewerSheetVisible = false
                    showPaymentDialog = false
                }
                else -> { /* other events */ }
            }
        }
    }

    val isWaiting = message == null && errorMessage == null
    val hasError = errorMessage != null
    val isConnected = message != null && session != "Logged Out" && !hasError
    val isPasskeyAuthenticated = session == "Connected"
    val shouldShowViewerSheet = isConnected && isPasskeyAuthenticated && isViewerSheetVisible
    val isConnecting = message != null && session == "Logged Out" && !hasError

    LaunchedEffect(pendingMppConsentFromState) {
        if (pendingMppConsentFromState != null) {
            showPaymentDialog = true
            isViewerSheetVisible = true
            isPaymentProcessing = false
        }
    }

    val viewerBottomSheetState =
        rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
        )

    val viewerCameraPreview: (@Composable () -> Unit)? =
        videoFrame?.let { frame ->
            {
                val aspectRatio = if (frame.height > 0) frame.width.toFloat() / frame.height else 4f / 3f
                Log.d("AnswerScreen", "Rendering viewer frame ${frame.width}x${frame.height}, bytes=${frame.data.size}")
                VideoFrameDisplay(
                    frameData = frame.data,
                    aspectRatio = aspectRatio,
                )
            }
        }

    Box(modifier = Modifier.fillMaxSize()) {
        if (shouldShowViewerSheet) {
            ModalBottomSheet(
                onDismissRequest = {},
                sheetState = viewerBottomSheetState,
                dragHandle = null,
                containerColor = Color.Transparent,
                contentWindowInsets = {
                    androidx.compose.foundation.layout
                        .WindowInsets(0, 0, 0, 0)
                },
            ) {
                LiquidStreamViewerScreen(
                    sessionId = session,
                    cameraPreview = viewerCameraPreview,
                    viewerAddress = accountAddress,
                    originUrl = message?.origin.orEmpty().ifBlank { "-" },
                    currentBlockNumber = currentBlockNumber,
                    remainingBalanceUsdc = viewerSessionVaultMicroUsdc / 1_000_000.0,
                    progressBalanceUsdc = viewerProgressBalanceMicroUsdc / 1_000_000.0,
                    onMinimize = {
                        Log.d("AnswerScreen", "Viewer minimize tapped. hasFrame=${videoFrame != null}")
                        onMinimizeToPip()
                    },
                )
            }
        }

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
            paymentBalance = null,
            fundsDepleted = false,
            isSolanaAccount = isSolanaAccount,
        )

        // Session Vault modal reused for MPP consent approval.
        val mppConsent = pendingMppConsentFromState
        if (showPaymentDialog && mppConsent != null) {
            val amountMicro = mppConsent.amount.toLongOrNull() ?: 0L
            val defaultTopUpMicro = 1_000_000L
            val amountText = (defaultTopUpMicro / 1_000_000.0).toString()
            Log.d("AnswerScreen", "🎭 Showing MPP consent dialog")
            LiquidAuthSessionVaultModal(
                initialAmount = amountText,
                quickAmounts = listOf(amountText, "8.0"),
                currencyLabel = "USDC",
                isProcessing = isPaymentProcessing,
                isDismissible = false,
                onDismiss = {
                    if (!isPaymentProcessing) {
                        viewModel.rejectMppConsent()
                        showPaymentDialog = false
                    }
                },
                onTopUpAndStream = { enteredAmount ->
                    if (isPaymentProcessing) return@LiquidAuthSessionVaultModal
                    isPaymentProcessing = true
                    scope.launch {
                        try {
                            val entered = enteredAmount.toDoubleOrNull() ?: (defaultTopUpMicro / 1_000_000.0)
                            val micro = (entered * 1_000_000.0).roundToLong().coerceAtLeast(1L)
                            val perSegmentMicro = amountMicro.coerceAtLeast(1L)
                            val maxSegments = (micro / perSegmentMicro).toInt().coerceAtLeast(1)
                            val voucherMessage =
                                MppPayments.buildClaimMessage(
                                    appId = com.michaeltchuang.walletsdk.core.railmpp.utils.RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                                    totalAmountClaimedMicroUsdc = micro,
                                )
                            val voucherSignature = viewModel.signFido2Challenge(voucherMessage, accountAddress)

                            viewModel.approveMppConsent(
                                ConsentApproval(
                                    approved = true,
                                    autoPaySegments = true,
                                    budgetCap = BudgetCap(amount = micro.toString(), asset = USDC_TESTNET_ID.toString()),
                                    maxAutoPaySegments = maxSegments,
                                    voucherSignature = voucherSignature,
                                ),
                            )
                            showPaymentDialog = false
                        } finally {
                            isPaymentProcessing = false
                        }
                    }
                },
            )
        }
    }
}

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
    paymentBalance: String? = null,
    fundsDepleted: Boolean = false,
    isSolanaAccount: Boolean = false,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                modifier = Modifier.fillMaxWidth(),
                text = stringResource(R.string.liquid_auth_header),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = AlgoKitTheme.colors.linkPrimary,
                textAlign = TextAlign.Center,
            )

            // Main content
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
            ) {
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

                // MPP Payment Status (when paid streaming)
                if (paymentBalance != null) {
                    PaymentStatusCard(
                        balance = paymentBalance,
                        fundsDepleted = fundsDepleted,
                        isSolanaAccount = isSolanaAccount,
                    )
                }

                // Account Info Card
                if (accountAddress.isNotEmpty()) {
                    AccountInfoCard(accountAddress = accountAddress)
                }
            }
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
    isSolanaAccount: Boolean = false,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = if (fundsDepleted) AlgoKitTheme.colors.negativeLighter else AlgoKitTheme.colors.positiveLighter,
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
                    color = if (fundsDepleted) AlgoKitTheme.colors.negative else AlgoKitTheme.colors.positive,
                )
                Text(
                    text = if (fundsDepleted) "Stream stopped" else "$balance ${if (isSolanaAccount) "SOL" else "ALGO"} remaining",
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
                            .background(AlgoKitTheme.colors.positive),
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
            hasError -> "Error: $errorMessage" to AlgoKitTheme.colors.negative
            isConnected -> "Connected to $session" to AlgoKitTheme.colors.positive
            isConnecting -> "Connecting..." to AlgoKitTheme.colors.success
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

@PreviewLightDark()
@Composable
private fun ScreenContentAnswerPreviewWaiting() {
    AlgoKitTheme {
        ScreenContentAnswer(
            isConnected = false,
            isWaiting = true,
            isConnecting = false,
            hasError = false,
            errorMessage = null,
            session = "Logged Out",
            origin = null,
            requestId = null,
            accountAddress = "A1B2C3D4E5F6G7H8I9J0",
        )
    }
}

@PreviewLightDark()
@Composable
private fun ScreenContentAnswerPreviewConnected() {
    AlgoKitTheme {
        ScreenContentAnswer(
            isConnected = true,
            isWaiting = false,
            isConnecting = false,
            hasError = false,
            errorMessage = null,
            session = "Connected Session",
            origin = "https://demo.algokit.io",
            requestId = "preview-req-123",
            accountAddress = "A1B2C3D4E5F6G7H8I9J0",
            paymentBalance = "0.8",
            fundsDepleted = false,
        )
    }
}
