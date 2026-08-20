package com.michaeltchuang.walletsdk.ui.liquidAuth.screens

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.michaeltchuang.walletsdk.ui.liquidAuth.state.ConnectionStatusState
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.AnswerViewModel
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.StreamHeartbeatVideoSink
import com.michaeltchuang.walletsdk.ui.liquidStream.components.ViewerMppConsentDialog
import com.michaeltchuang.walletsdk.ui.liquidStream.components.WebRtcVideoRenderer
import com.michaeltchuang.walletsdk.ui.liquidStream.screens.LiquidStreamViewerScreen
import com.michaeltchuang.walletsdk.ui.liquidStream.utils.LIQUID_AUTH_SESSION
import com.michaeltchuang.walletsdk.ui.liquidStream.utils.SESSION_LOGGED_OUT
import com.michaeltchuang.walletsdk.ui.liquidStream.viewmodels.LiquidAuthViewerViewModel
import kotlinx.coroutines.delay
import org.koin.compose.viewmodel.koinViewModel
import org.webrtc.EglBase
import org.webrtc.VideoTrack
import kotlin.time.Duration.Companion.milliseconds

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
    val viewerViewModel: LiquidAuthViewerViewModel = koinViewModel()
    val session by viewModel.session.collectAsState()
    val message by viewModel.authMessage.collectAsState()
    val accountAddress by viewModel.accountAddress.collectAsState()
    val errorMessage by viewModel.error.collectAsState()
    val signalService by viewModel.signalService.collectAsState()
    val viewerSessionVaultMicroUsdc by viewModel.viewerSessionVaultMicroUsdc.collectAsState()
    val viewerProgressBalanceMicroUsdc by viewModel.viewerProgressBalanceMicroUsdc.collectAsState()
    val currentBlockNumber by viewModel.currentBlockNumber.collectAsState()
    var deliveredMessageCount by remember { mutableIntStateOf(0) }
    val streamHostUiModeState = remember { mutableStateOf(StreamHostUiMode.Hidden) }
    val miniPlayerCameraPreviewState = remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    var remoteVideoTrack by remember { mutableStateOf<VideoTrack?>(null) }
    var eglBaseContext by remember { mutableStateOf<EglBase.Context?>(null) }
    val viewerBottomSheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = {it != SheetValue.Hidden },
        )

    var isViewerSheetVisible by rememberSaveable { mutableStateOf(true) }
    val hasError = errorMessage != null
    val isConnected = message != null && session != SESSION_LOGGED_OUT && !hasError
    val isPasskeyAuthenticated = session == LIQUID_AUTH_SESSION
    val shouldShowViewerSheet = isConnected && isPasskeyAuthenticated && isViewerSheetVisible

    LaunchedEffect(signalService) {
        val service = signalService ?: return@LaunchedEffect
        service.setOnRemoteVideoTrack { track ->
            remoteVideoTrack = track
        }
        while (true) {
            if (eglBaseContext == null) {
                service.eglBaseContext?.let { eglBaseContext = it }
            }
            val track = service.remoteVideoTrack
            if (track !== remoteVideoTrack) {
                remoteVideoTrack = track
            }
            // Re-register the listener once the peerClient exists (it may not have on first pass).
            if (track == null) {
                service.setOnRemoteVideoTrack { t -> remoteVideoTrack = t }
            }
            delay(300.milliseconds)
        }
    }

    // Detect when the host stops streaming: mark the shared stream-timeout watchdog active
    // whenever a real frame is delivered on the native remote video track. If the host stops
    // its camera/ends the stream and frames stop arriving, the existing STREAM_TIMEOUT_MS watchdog in
    // LiquidAuthViewerStateHolder fires onStreamTimeout -> StreamDisconnected, tearing down
    // the viewer session automatically (handled below).
    DisposableEffect(remoteVideoTrack) {
        val track = remoteVideoTrack
        val heartbeatSink = StreamHeartbeatVideoSink(onHeartbeat = { viewModel.markStreamFrameReceived() })
        runCatching { track?.addSink(heartbeatSink) }
        onDispose {
            runCatching { track?.removeSink(heartbeatSink) }
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.startRealtimeBlockNumberUpdates()
        Log.d("AnswerScreen", "Starting to collect view events...")
        viewModel.viewEvent.collect { event ->
            Log.d("AnswerScreen", "View event received: ${event::class.simpleName}")
            when (event) {
                is AnswerViewModel.ViewEvent.StreamDisconnected -> {
                    Log.w("AnswerScreen", "Viewer stream disconnected: ${event.reason}")
                    isViewerSheetVisible = false
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

    LaunchedEffect(streamHostUiModeState.value) {
        if (streamHostUiModeState.value == StreamHostUiMode.Expanded) {
            isViewerSheetVisible = true
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.chatMessages.collect { messages ->
            if (messages.size > deliveredMessageCount) {
                messages.drop(deliveredMessageCount).forEach {
                    viewerViewModel.receivedChatMessage(it)
                }
                deliveredMessageCount = messages.size
            }
        }
    }

    val viewerCameraPreview: (@Composable () -> Unit)? =
        remoteVideoTrack?.let { track ->
            {
                WebRtcVideoRenderer(
                    eglBaseContext = eglBaseContext,
                    videoTrack = track,
                    mirror = true,
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
                        Log.d("AnswerScreen", "Viewer minimize tapped. hasTrack=${remoteVideoTrack != null}")
                        isViewerSheetVisible = false
                        miniPlayerCameraPreviewState.value = viewerCameraPreview
                        streamHostUiModeState.value = StreamHostUiMode.Minimized
                        onMinimizeToPip()
                    },
                    onTopUpConfirm = onViewerTopUpConfirm,
                    onSendClick = { text ->
                        viewModel.sendChatMessage(text)
                    },
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

        ViewerMppConsentDialog(stateHolder = viewModel)
    }
}
