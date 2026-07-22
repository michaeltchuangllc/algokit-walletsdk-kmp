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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.UIKitView
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAccountAlgoBalance
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAlgo25SecretKey
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetFalcon24SecretKey
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetHdSeed
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetLocalAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetLocalAccounts
import com.michaeltchuang.walletsdk.core.network.domain.usecase.GetCurrentNetworkUseCase
import com.michaeltchuang.walletsdk.core.network.usecase.GetCurrentBlockUseCase
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetRemainingSessionVaultBalanceUseCase
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetSessionVaultConfigUseCase
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.MppWalletSignerUseCase
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.liquidAuth.state.AnswerScreenState
import com.michaeltchuang.walletsdk.ui.liquidAuth.state.ConnectionStatusState
import com.michaeltchuang.walletsdk.ui.liquidAuth.service.LiquidAuthConnectionManager
import com.michaeltchuang.walletsdk.ui.liquidAuth.service.activeIOSViewerConnectionManager
import com.michaeltchuang.walletsdk.ui.liquidAuth.service.iosViewerVideoViewProvider
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.AnswerViewModel
import com.michaeltchuang.walletsdk.ui.liquidStream.domain.manager.MppPaymentViewerManager
import com.michaeltchuang.walletsdk.ui.liquidStream.domain.usecases.SetupMppPaymentViewerUseCase
import com.michaeltchuang.walletsdk.ui.liquidStream.components.ViewerMppConsentDialog
import com.michaeltchuang.walletsdk.ui.liquidStream.screens.LiquidStreamViewerScreen
import kotlinx.coroutines.runBlocking
import org.koin.compose.koinInject
import platform.Foundation.NSLog
import platform.UIKit.UIView
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val TAG = "AnswerScreenOverlayiOS"

@OptIn(ExperimentalEncodingApi::class)
@Composable
actual fun AnswerScreenOverlay() {
    if (!AnswerScreenState.isVisible) return

    val address = AnswerScreenState.accountAddress
    val origin = AnswerScreenState.origin

    val viewerManager: LiquidAuthConnectionManager = koinInject()
    // Inject use cases from Koin so the shared CommonAnswerViewModel can be constructed.
    val getCurrentBlockUseCase: GetCurrentBlockUseCase = koinInject()
    val getAccountAlgoBalance: GetAccountAlgoBalance = koinInject()
    val getLocalAccount: GetLocalAccount = koinInject()
    val getLocalAccounts: GetLocalAccounts = koinInject()
    val getAlgo25SecretKey: GetAlgo25SecretKey = koinInject()
    val getFalcon24SecretKey: GetFalcon24SecretKey = koinInject()
    val getSeed: GetHdSeed = koinInject()
    val getCurrentNetworkUseCase: GetCurrentNetworkUseCase = koinInject()
    val getRemainingSessionVaultBalanceUseCase: GetRemainingSessionVaultBalanceUseCase = koinInject()
    val getSessionVaultConfigUseCase: GetSessionVaultConfigUseCase = koinInject()
    val setupMppPaymentViewerUseCase: SetupMppPaymentViewerUseCase = koinInject()
    val mppPaymentViewerManager: MppPaymentViewerManager = koinInject()
    val mppWalletSignerUseCase: MppWalletSignerUseCase = koinInject()

    // Scoped to a per-overlay ViewModelStore so its stream-timeout monitor and block-number
    // polling are cancelled when the overlay is dismissed.
    val viewModelStoreOwner =
        remember {
            object : ViewModelStoreOwner {
                override val viewModelStore = ViewModelStore()
            }
        }

    // AnswerViewModel is scoped to this overlay so stream-timeout monitoring and block polling
    // are cancelled when the overlay is dismissed. This avoids the Android-only Compose viewModel API.
    val stateHolder =
        remember(viewModelStoreOwner) {
            AnswerViewModel(
                getCurrentBlockUseCase = getCurrentBlockUseCase,
                getAccountAlgoBalance = getAccountAlgoBalance,
                getLocalAccount = getLocalAccount,
                getLocalAccounts = getLocalAccounts,
                getAlgo25SecretKey = getAlgo25SecretKey,
                getFalcon24SecretKey = getFalcon24SecretKey,
                getSeed = getSeed,
                getCurrentNetworkUseCase = getCurrentNetworkUseCase,
                getRemainingSessionVaultBalanceUseCase = getRemainingSessionVaultBalanceUseCase,
                getSessionVaultConfigUseCase = getSessionVaultConfigUseCase,
                setupMppPaymentViewerUseCase = setupMppPaymentViewerUseCase,
                mppPaymentViewerManager = mppPaymentViewerManager,
                mppWalletSignerUseCase = mppWalletSignerUseCase,
            ).also { viewModelStoreOwner.viewModelStore.put("AnswerViewModel", it) }
        }

    // Viewer UI state is read from the shared holder; the iOS manager only pushes transport updates into it.
    val remainingBalance by stateHolder.viewerSessionVaultMicroUsdc.collectAsStateWithLifecycle()
    val progressBalance by stateHolder.viewerProgressBalanceMicroUsdc.collectAsStateWithLifecycle()
    val currentBlockNumber by stateHolder.currentBlockNumber.collectAsStateWithLifecycle()
    val connType by stateHolder.connectionType.collectAsStateWithLifecycle()
    val sessionId by stateHolder.session.collectAsStateWithLifecycle()
    var remoteVideoView by remember { mutableStateOf<UIView?>(null) }
    val streamHostUiModeState = remember { mutableStateOf(StreamHostUiMode.Hidden) }
    val miniPlayerCameraPreviewState = remember { mutableStateOf<(@Composable () -> Unit)?>(null) }

    LaunchedEffect(viewerManager) {
        activeIOSViewerConnectionManager = viewerManager

        // Reset shared state for this session and route the holder's stream-timeout to teardown,
        // mirroring Android's auto-disconnect when no frames arrive for a few seconds.
        stateHolder.clearVideoFrame()
        viewerManager.attachAnswerViewModel(stateHolder)
        stateHolder.onTimeout = { dismissOverlay(viewerManager) }
        if (address.isNotBlank()) {
            stateHolder.setAccountAddress(address)
        }

        // Start block-number polling so iOS shows the same live block counter as Android.
        stateHolder.startRealtimeBlockNumberUpdates()

        // Fetch account balance for the viewer address.
        if (address.isNotBlank()) {
            stateHolder.fetchAccountBalance()
        }

        // Provide the Ed25519 public key so the viewer hello message can carry it, enabling
        // correct on-chain session-vault balance lookups.
        if (!stateHolder.platformServices.hasViewerPublicKeyProvider()) {
            stateHolder.platformServices.setViewerPublicKeyProvider { addr ->
                runCatching {
                    runBlocking {
                        stateHolder
                            .buildMppWalletSigner(addr)
                            ?.authorizedSignerPublicKey
                            ?.let { Base64.encode(it) }
                    }
                }.getOrNull()
            }
        }

        // Set the viewer address BEFORE notifyViewerConnected().
        if (address.isNotBlank()) {
            viewerManager.setViewerAddress(address)
          //  viewerManager.startViewerBalancePollingSafe(address, stateHolder.hostAddress.value)
        }

        NSLog(
            "$TAG: init viewerAddress='$address' origin='$origin' " +
                "_viewerAddress='${stateHolder.viewerAddress.value}'",
        )

        // The native WebRTC connection is already being established by the Swift handler;
        // notify the manager so it sends the hello message and starts balance polling.
        viewerManager.notifyViewerConnected()
        // The remote track may arrive after the data channel opens, so retry briefly until the
        // Swift WebRTC service can create its RTCMTLVideoView renderer.
        while (remoteVideoView == null) {
            remoteVideoView = iosViewerVideoViewProvider?.invoke() as? UIView
            if (remoteVideoView == null) kotlinx.coroutines.delay(300)
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
            stateHolder.stopRealtimeBlockNumberUpdates()
            viewerManager.attachAnswerViewModel(null)
            viewerManager.disconnectViewer()
            viewerManager.clearActiveViewerIfCurrent()
            ConnectionStatusState.onDisconnect = null
            // Cancels the holder's viewModelScope (stream-timeout monitor + block polling).
            viewModelStoreOwner.viewModelStore.clear()
        }
    }

    AlgoKitTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            val viewerCameraPreview: (@Composable () -> Unit) = {
                val renderer = remoteVideoView
                if (renderer != null) {
                    UIKitView(
                        factory = { renderer },
                        modifier = Modifier.fillMaxSize(),
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
                    currentBlockNumber = currentBlockNumber,
                    onMinimize = {
                        miniPlayerCameraPreviewState.value = viewerCameraPreview
                        streamHostUiModeState.value = StreamHostUiMode.Minimized
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

            ViewerMppConsentDialog(stateHolder = stateHolder)
        }
    }
}

/**
 * Tears down the active viewer connection and hides the overlay + connection-status bar.
 */
private fun dismissOverlay(viewerManager: LiquidAuthConnectionManager) {
    viewerManager.disconnectViewer()
    viewerManager.clearActiveViewerIfCurrent()
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
