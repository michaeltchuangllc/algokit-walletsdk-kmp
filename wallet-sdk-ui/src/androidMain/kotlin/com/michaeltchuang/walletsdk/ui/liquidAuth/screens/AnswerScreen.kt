package com.michaeltchuang.walletsdk.ui.liquidAuth.screens

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.michaeltchuang.walletsdk.core.deeplink.utils.AssetConstants.USDC_TESTNET_ID
import com.michaeltchuang.walletsdk.core.railmpp.core.BudgetCap
import com.michaeltchuang.walletsdk.core.railmpp.core.ConsentApproval
import com.michaeltchuang.walletsdk.core.railmpp.utils.MppPayments
import com.michaeltchuang.walletsdk.ui.liquidAuth.components.LiquidAuthSessionVaultModal
import com.michaeltchuang.walletsdk.ui.liquidAuth.components.VideoFrameDisplay
import com.michaeltchuang.walletsdk.ui.liquidAuth.state.ConnectionStatusState
import com.michaeltchuang.walletsdk.ui.liquidAuth.utils.LIQUID_AUTH_SESSION
import com.michaeltchuang.walletsdk.ui.liquidAuth.utils.SESSION_LOGGED_OUT
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.AnswerViewModel
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
    onViewerTopUpConfirm: (String) -> Unit = {},
    onViewerClose: () -> Unit = {},
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

    val streamHostUiModeState = remember { mutableStateOf(StreamHostUiMode.Hidden) }
    val miniPlayerCameraPreviewState = remember { mutableStateOf<(@Composable () -> Unit)?>(null) }

    var showPaymentDialog by remember { mutableStateOf(false) }
    var isPaymentProcessing by remember { mutableStateOf(false) }
    var isViewerSheetVisible by rememberSaveable { mutableStateOf(true) }
    val hasError = errorMessage != null
    val isConnected = message != null && session != SESSION_LOGGED_OUT && !hasError
    val isPasskeyAuthenticated = session == LIQUID_AUTH_SESSION
    val shouldShowViewerSheet = isConnected && isPasskeyAuthenticated && isViewerSheetVisible
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
                    ConnectionStatusState.isVisible = false
                    ConnectionStatusState.isExpanded = false
                    ConnectionStatusState.session = ""
                    ConnectionStatusState.origin = ""
                    ConnectionStatusState.requestId = ""
                    ConnectionStatusState.accountAddress = ""
                }
                else -> { /* other events */ }
            }
        }
    }

    LaunchedEffect(pendingMppConsentFromState) {
        if (pendingMppConsentFromState != null) {
            showPaymentDialog = true
            isViewerSheetVisible = true
            isPaymentProcessing = false
        }
    }

    LaunchedEffect(streamHostUiModeState.value) {
        if (streamHostUiModeState.value == StreamHostUiMode.Expanded) {
            isViewerSheetVisible = true
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
                        isViewerSheetVisible = false
                        miniPlayerCameraPreviewState.value = viewerCameraPreview
                        streamHostUiModeState.value = StreamHostUiMode.Minimized
                        onMinimizeToPip()
                    },
                    onTopUpConfirm = onViewerTopUpConfirm,
                )
            }
        }

        LiquidAuthMiniPlayerOverlay(
            streamHostUiModeState = streamHostUiModeState,
            miniPlayerCameraPreviewState = miniPlayerCameraPreviewState,
            onClose = {
                streamHostUiModeState.value = StreamHostUiMode.Hidden
                viewModel.signalService.value?.stop()
                ConnectionStatusState.isVisible = false
                ConnectionStatusState.isExpanded = false
                ConnectionStatusState.session = ""
                ConnectionStatusState.origin = ""
                ConnectionStatusState.requestId = ""
                ConnectionStatusState.accountAddress = ""
                onViewerClose()
            },
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
                            val hostAddress = mppConsent.payTo.orEmpty()
                            if (hostAddress.isBlank()) {
                                viewModel.rejectMppConsent()
                                showPaymentDialog = false
                                return@launch
                            }
                            val voucherMessage =
                                MppPayments.buildClaimMessage(
                                    appId = com.michaeltchuang.walletsdk.core.railmpp.utils.RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                                    viewerAddress = accountAddress,
                                    hostAddress = hostAddress,
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
