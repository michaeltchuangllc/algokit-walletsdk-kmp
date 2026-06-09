package com.michaeltchuang.walletsdk.ui.liquidAuth.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.michaeltchuang.walletsdk.core.railmpp.MppClientConfig
import com.michaeltchuang.walletsdk.core.railmpp.MppNetworks
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.liquidAuth.state.AnswerScreenState
import com.michaeltchuang.walletsdk.ui.liquidAuth.state.ConnectionStatusState
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.LiquidAuthViewerStateHolder
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.VideoFrameData
import com.michaeltchuang.walletsdk.ui.liquidStream.IOSLiquidStreamViewerConnectionManager
import com.michaeltchuang.walletsdk.ui.liquidStream.IosViewerPaymentOrchestrator
import com.michaeltchuang.walletsdk.ui.liquidStream.activeIOSViewerConnectionManager
import com.michaeltchuang.walletsdk.ui.liquidStream.components.LiquidAuthSessionVaultModal
import com.michaeltchuang.walletsdk.ui.liquidStream.components.VideoFrameDisplay
import com.michaeltchuang.walletsdk.ui.liquidStream.iosViewerDepositHandler
import com.michaeltchuang.walletsdk.ui.liquidStream.iosViewerPublicKeyProvider
import com.michaeltchuang.walletsdk.ui.liquidStream.screens.LiquidStreamViewerScreen
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.compose.koinInject
import platform.Foundation.NSLog
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val TAG = "AnswerScreenOverlayiOS"

/**
 * iOS implementation of the Liquid Auth viewer overlay.
 *
 * Mirrors the Android [AnswerScreenOverlay] / `AnswerScreen` flow: it renders the
 * [LiquidStreamViewerScreen] (plus the MPP consent/deposit modal and a draggable mini player)
 * directly in Compose at the root of the UI, surviving bottom-sheet dismissal and navigation
 * changes.
 *
 * The actual WebRTC connection is established natively (via `LiquidAuthService.swift`) when
 * [com.michaeltchuang.walletsdk.ui.liquidAuth.connectLiquidAuth] notifies the registered
 * `iosLiquidAuthHandler`; this overlay binds to that connection through
 * [IOSLiquidStreamViewerConnectionManager].
 */
@OptIn(ExperimentalEncodingApi::class)
@Composable
actual fun AnswerScreenOverlay() {
    if (!AnswerScreenState.isVisible) return

    val address = AnswerScreenState.accountAddress
    val origin = AnswerScreenState.origin

    val viewerManager = remember { IOSLiquidStreamViewerConnectionManager() }
    val paymentOrchestrator: IosViewerPaymentOrchestrator = koinInject()
    val scope = rememberCoroutineScope()

    // Shared, cross-platform state layer (same `LiquidAuthViewerStateHolder` that Android's
    // `AnswerViewModel` extends). Scoped to a per-overlay ViewModelStore so its stream-timeout
    // monitor is cancelled when the overlay is dismissed.
    val viewModelStoreOwner =
        remember {
            object : ViewModelStoreOwner {
                override val viewModelStore = ViewModelStore()
            }
        }
    val stateHolder: IosLiquidAuthViewerStateHolder =
        viewModel(viewModelStoreOwner) { IosLiquidAuthViewerStateHolder() }

    // Video frames + session-vault progress are read from the shared holder; connection-specific
    // state (consent flow, ICE type, session id) stays on the iOS connection manager.
    val frame by stateHolder.videoFrame.collectAsStateWithLifecycle()
    val remainingBalance by stateHolder.viewerSessionVaultMicroUsdc.collectAsStateWithLifecycle()
    val progressBalance by stateHolder.viewerProgressBalanceMicroUsdc.collectAsStateWithLifecycle()
    val connType by viewerManager.connectionType.collectAsStateWithLifecycle()
    val sessionId by viewerManager.sessionId.collectAsStateWithLifecycle()
    val pendingConsent by viewerManager.pendingMppConsent.collectAsStateWithLifecycle()
    val isPaymentProcessing by viewerManager.isPaymentProcessing.collectAsStateWithLifecycle()

    var showPaymentDialog by remember { mutableStateOf(false) }
    val streamHostUiModeState = remember { mutableStateOf(StreamHostUiMode.Hidden) }
    val miniPlayerCameraPreviewState = remember { mutableStateOf<(@Composable () -> Unit)?>(null) }

    LaunchedEffect(viewerManager) {
        activeIOSViewerConnectionManager = viewerManager

        // Reset shared state for this session and route the holder's stream-timeout to teardown,
        // mirroring Android's auto-disconnect when no frames arrive for a few seconds.
        stateHolder.clearVideoFrame()
        stateHolder.onTimeout = { dismissOverlay(viewerManager) }
        if (address.isNotBlank()) {
            stateHolder.setAccountAddress(address)
        }

        // Provide the Ed25519 public key so the viewer hello message can carry it, enabling
        // correct on-chain session-vault balance lookups.
        if (iosViewerPublicKeyProvider == null) {
            iosViewerPublicKeyProvider = { addr ->
                runCatching {
                    runBlocking {
                        paymentOrchestrator
                            .buildWalletSigner(addr)
                            ?.authorizedSignerPublicKey
                            ?.let { Base64.encode(it) }
                    }
                }.getOrNull()
            }
        }

        // Set the viewer address BEFORE notifyConnected() so sendViewerHello() can include it.
        if (address.isNotBlank()) {
            viewerManager.setViewerAddress(address)
            viewerManager.startBalancePollingSafe(address, viewerManager.hostAddress.value)
        }

        // Set up the MPP payment rail so the iOS viewer uses PaywalledRTCClient for ALL
        // PaywalledRTCServer hosts — both Android and iOS hosts with iosBroadcastUsePaywalledRTCServer=true.
        // The rail is created with the viewer's wallet signer so that createRailPayment() can
        // sign the per-segment MPP credential and send segment:payment back to the host.
        if (address.isNotBlank()) {
            runCatching {
                val signer = runBlocking { paymentOrchestrator.buildWalletSigner(address) }
                if (signer != null) {
                    val mppClientConfig = MppClientConfig(
                        network = MppNetworks.ALGORAND_TESTNET,
                        signer = signer,
                    )
                    viewerManager.setupPaymentRail(mppClientConfig)
                    NSLog("$TAG:  IOSLiquidStreamViewer MPP payment rail configured for viewer=$address")
                } else {
                    NSLog("$TAG: Could not build wallet signer for $address — PaywalledRTCClient rail not set")
                }
            }.onFailure { e ->
                NSLog("$TAG: Failed to set up PaywalledRTCClient payment rail: ${e.message}")
            }
        }

        // Wire the deposit handler: performs the on-chain deposit + sends a voucher to the host.
        iosViewerDepositHandler = { viewerAddr, hostAddr, depositMicroUsdc, callback ->
            paymentOrchestrator.depositAndSendVoucher(
                viewerAddress = viewerAddr,
                hostAddress = hostAddr,
                sessionId = viewerManager.sessionId.value,
                depositMicroUsdc = depositMicroUsdc,
                sendMessageFn = { msg -> viewerManager.sendMessage(msg) },
                onResult = callback,
            )
        }

        NSLog(
            "$TAG: init viewerAddress='$address' origin='$origin' " +
                "_viewerAddress='${viewerManager.viewerAddress.value}'",
        )

        // The native WebRTC connection is already being established by the Swift handler;
        // notify the manager so it sends the hello message and starts balance polling.
        viewerManager.notifyConnected()
    }

    // Show the consent dialog when a payment request arrives.
    LaunchedEffect(pendingConsent) {
        if (pendingConsent != null) {
            showPaymentDialog = true
        }
    }

    // Bridge the iOS connection manager's connection-specific flows into the shared holder so the
    // UI renders from one cross-platform state layer and the holder's timeout monitor sees frames.
    LaunchedEffect(viewerManager, stateHolder) {
        launch {
            viewerManager.latestVideoFrame.collect { vf ->
                if (vf != null) {
                    stateHolder.setVideoFrame(
                        VideoFrameData(
                            id = vf.id,
                            timestamp = vf.timestamp,
                            data = vf.data,
                            width = vf.width,
                            height = vf.height,
                            format = vf.format,
                        ),
                    )
                } else {
                    stateHolder.clearVideoFrame()
                }
            }
        }
        launch {
            combine(
                viewerManager.remainingBalanceMicroUsdc,
                viewerManager.progressBalanceMicroUsdc,
            ) { remaining, progress -> remaining to progress }
                .collect { (remaining, progress) ->
                    stateHolder.setViewerSessionVaultProgress(remaining, progress)
                }
        }
        launch {
            viewerManager.sessionId.collect { sid ->
                stateHolder.setSession(sid.ifBlank { null })
            }
        }
    }

    // Keep the demo app's connection-status bar in sync, mirroring Android.
    LaunchedEffect(sessionId, origin, address) {
        ConnectionStatusState.isVisible = AnswerScreenState.isVisible
        ConnectionStatusState.session = sessionId
        ConnectionStatusState.origin = origin
        ConnectionStatusState.requestId = AnswerScreenState.requestId
        ConnectionStatusState.accountAddress = address
    }

    DisposableEffect(viewerManager) {
        ConnectionStatusState.onDisconnect = {
            dismissOverlay(viewerManager)
        }
        onDispose {
            viewerManager.disconnect()
            if (activeIOSViewerConnectionManager === viewerManager) {
                activeIOSViewerConnectionManager = null
            }
            ConnectionStatusState.onDisconnect = null
            // Cancels the holder's viewModelScope (stream-timeout monitor).
            viewModelStoreOwner.viewModelStore.clear()
        }
    }

    AlgoKitTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            val viewerCameraPreview: (@Composable () -> Unit) = {
                val currentFrame = frame
                if (currentFrame != null && currentFrame.height > 0) {
                    VideoFrameDisplay(
                        frameData = currentFrame.data,
                        aspectRatio = currentFrame.width.toFloat() / currentFrame.height.toFloat(),
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black))
                }
            }

            if (streamHostUiModeState.value != StreamHostUiMode.Minimized) {
                LiquidStreamViewerScreen(
                    sessionId = sessionId,
                    connectionType = connType,
                    cameraPreview = viewerCameraPreview,
                    viewerAddress = address.ifBlank { "-" },
                    originUrl = origin.ifBlank { "-" },
                    remainingBalanceUsdc = remainingBalance / 1_000_000.0,
                    progressBalanceUsdc = progressBalance / 1_000_000.0,
                    onMinimize = {
                        miniPlayerCameraPreviewState.value = viewerCameraPreview
                        streamHostUiModeState.value = StreamHostUiMode.Minimized
                    },
                    onTopUpConfirm = { enteredAmountUsdc ->
                        scope.launch {
                            viewerManager.approveMppConsent(enteredAmountUsdc)
                        }
                    },
                )
            }

            LiquidAuthMiniPlayerOverlay(
                streamHostUiModeState = streamHostUiModeState,
                miniPlayerCameraPreviewState = miniPlayerCameraPreviewState,
                onClose = {
                    streamHostUiModeState.value = StreamHostUiMode.Hidden
                    dismissOverlay(viewerManager)
                },
            )

            // MPP consent / deposit dialog — shown when the host requests payment.
            val consent = pendingConsent
            if (showPaymentDialog && consent != null) {
                val amountText = "1.0"
                LiquidAuthSessionVaultModal(
                    initialAmount = amountText,
                    quickAmounts = listOf(amountText, "8.0"),
                    currencyLabel = "USDC",
                    isProcessing = isPaymentProcessing,
                    isDismissible = false,
                    onDismiss = {
                        if (!isPaymentProcessing) {
                            viewerManager.rejectMppConsent()
                            showPaymentDialog = false
                        }
                    },
                    onTopUpAndStream = { enteredAmount ->
                        if (!isPaymentProcessing) {
                            scope.launch {
                                viewerManager.approveMppConsent(enteredAmount)
                                showPaymentDialog = false
                            }
                        }
                    },
                )
            }
        }
    }
}

/**
 * iOS [LiquidAuthViewerStateHolder] that routes the shared stream-timeout to the overlay so the
 * underlying connection is torn down when no video frames arrive (parity with Android).
 */
private class IosLiquidAuthViewerStateHolder : LiquidAuthViewerStateHolder() {
    var onTimeout: (() -> Unit)? = null

    override fun onStreamTimeout(reason: String) {
        onTimeout?.invoke()
    }
}

/**
 * Tears down the active viewer connection and hides the overlay + connection-status bar.
 */
private fun dismissOverlay(viewerManager: IOSLiquidStreamViewerConnectionManager) {
    viewerManager.disconnect()
    if (activeIOSViewerConnectionManager === viewerManager) {
        activeIOSViewerConnectionManager = null
    }
    AnswerScreenState.isVisible = false
    AnswerScreenState.origin = ""
    AnswerScreenState.requestId = ""
    ConnectionStatusState.isVisible = false
    ConnectionStatusState.isExpanded = false
    ConnectionStatusState.session = ""
    ConnectionStatusState.origin = ""
    ConnectionStatusState.requestId = ""
    ConnectionStatusState.accountAddress = ""
}
