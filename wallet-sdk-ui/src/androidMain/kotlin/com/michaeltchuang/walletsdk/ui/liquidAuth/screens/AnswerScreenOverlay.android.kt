package com.michaeltchuang.walletsdk.ui.liquidAuth.screens

import android.app.NotificationManager
import android.content.Context
import android.os.StrictMode
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.michaeltchuang.walletsdk.core.foundation.utils.AppId
import com.michaeltchuang.walletsdk.core.liquidAuth.auth.fido2.WebAuthnCredential
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.liquidAuth.AuthMessageStorage
import com.michaeltchuang.walletsdk.ui.liquidAuth.configuration.IceServerConfig
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases.HandleAssertionResultUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases.HandleAttestationResultUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.state.AnswerScreenState
import com.michaeltchuang.walletsdk.ui.liquidAuth.state.ConnectionStatusState
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.AnswerViewModel
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.VideoFrameData
import com.michaeltchuang.walletsdk.ui.liquidStream.utils.LIQUID_AUTH_SESSION
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.json.JSONObject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.mp.KoinPlatform
import java.security.Security
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import java.util.Base64 as JavaBase64

private const val TAG = "AnswerScreenOverlay"

@OptIn(ExperimentalEncodingApi::class)
@Composable
actual fun AnswerScreenOverlay() {
    if (!AnswerScreenState.isVisible) return

    val context = LocalContext.current
    val activity = context as? AppCompatActivity ?: return
    val scope = rememberCoroutineScope()

    val viewModelStoreOwner =
        remember {
            object : ViewModelStoreOwner {
                override val viewModelStore = ViewModelStore()
            }
        }

    CompositionLocalProvider(
        LocalViewModelStoreOwner provides viewModelStoreOwner,
    ) {
        val viewModel: AnswerViewModel = koinViewModel()
        val address = AnswerScreenState.accountAddress

        val credentialManager = remember(context) { CredentialManager.create(context) }

        LaunchedEffect(Unit) {
            val policy =
                StrictMode.ThreadPolicy
                    .Builder()
                    .permitAll()
                    .build()
            StrictMode.setThreadPolicy(policy)
            Security.removeProvider("BC")
            Security.insertProviderAt(BouncyCastleProvider(), 0)

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            viewModel.createChannels(nm)
            viewModel.logAppSignature(context)
            viewModel.bindSignalService(context)

            if (address.isNotBlank()) {
                viewModel.setAccountAddress(address)
            }

            val msg = AuthMessageStorage.authMessage
            if (msg.origin.isNotEmpty() && msg.requestId.isNotEmpty()) {
                viewModel.setMessage(msg)
                viewModel.clearError()

                // Wait for SignalService to bind before starting the WebRTC flow.
                viewModel.signalService.filterNotNull().take(1).collect { service ->
                    service.start(
                        msg.origin,
                        viewModel.getProvideHttpClient(),
                        viewModel.createNotificationBuilder(activity),
                        AnswerViewModel.SERVICE_NOTIFICATION_ID,
                        null,
                    )

                    if (address.isNotBlank()) {
                        val savedCredential = viewModel.getCredentialIdByAccountAddress(address)
                        if (savedCredential == null) {
                            viewModel.registerPasskey(
                                authMessage = msg,
                                accountAddress = address,
                                options = JSONObject(),
                            )
                        } else {
                            viewModel.authenticate(
                                authMessage = msg,
                                credentialId = savedCredential,
                                setSession = { sessionId -> sessionId?.let { viewModel.setSession(it) } },
                                onCredentialNotFound = {
                                    scope.launch {
                                        viewModel.deleteCredentialByAccountAddress(address)
                                        Toast
                                            .makeText(
                                                context,
                                                "Credential not found on server. Re-registering...",
                                                Toast.LENGTH_LONG,
                                            ).show()
                                        viewModel.registerPasskey(
                                            authMessage = msg,
                                            accountAddress = address,
                                            options = JSONObject(),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        LaunchedEffect(viewModel) {
            var credentialOperation: Job? = null
            viewModel.viewEvent.collect { event ->
                when (event) {
                    is AnswerViewModel.ViewEvent.AttestationSuccess -> {
                        Napier.d("Attestation Success - setting up WebRTC", tag = TAG)
                        viewModel.setSession(LIQUID_AUTH_SESSION)
                        handleWebRTCSetup(
                            viewModel = viewModel,
                            activity = activity,
                            address = address,
                            credential = event.credential,
                        )
                    }

                    is AnswerViewModel.ViewEvent.AssertionSuccess -> {
                        Napier.d("Assertion Success - setting up WebRTC", tag = TAG)
                        viewModel.authMessage.value?.let { _ ->
                            viewModel.setSession(LIQUID_AUTH_SESSION)
                        }
                        handleWebRTCSetup(
                            viewModel = viewModel,
                            activity = activity,
                            address = address,
                            credential = event.credential,
                        )
                    }

                    is AnswerViewModel.ViewEvent.AttestationCancelled -> {
                        Toast.makeText(context, "Attestation cancelled.", Toast.LENGTH_SHORT).show()
                    }

                    is AnswerViewModel.ViewEvent.AttestationError -> {
                        Toast.makeText(context, "Attestation failed: ${event.message}", Toast.LENGTH_LONG).show()
                    }

                    is AnswerViewModel.ViewEvent.ShowToast -> {
                        Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                    }

                    is AnswerViewModel.ViewEvent.ShowError -> {
                        Toast.makeText(context, event.message, Toast.LENGTH_LONG).show()
                    }

                    is AnswerViewModel.ViewEvent.StreamDisconnected -> {
                        Toast.makeText(context, event.reason, Toast.LENGTH_LONG).show()
                        AnswerScreenState.isVisible = false
                        ConnectionStatusState.isVisible = false
                        ConnectionStatusState.isExpanded = false
                        ConnectionStatusState.session = ""
                        ConnectionStatusState.origin = ""
                        ConnectionStatusState.requestId = ""
                        ConnectionStatusState.accountAddress = ""
                    }

                    is AnswerViewModel.ViewEvent.TransactionSigned -> {
                        val cborBytes = viewModel.encodeResponseMessage(event.resultMessage)
                        val base64String = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(cborBytes)
                        viewModel.signalService.value?.send(base64String)
                        Toast.makeText(context, "Transactions signed successfully!", Toast.LENGTH_SHORT).show()
                    }

                    is AnswerViewModel.ViewEvent.RegistrationSuccess -> {
                        if (credentialOperation?.isActive == true) return@collect
                        // Launch separately: result handlers emit events to this same collector.
                        credentialOperation =
                            scope.launch {
                                try {
                                    val challenge = JavaBase64.getUrlDecoder().decode(JSONObject(event.requestJson).getString("challenge"))
                                    viewModel.currentChallenge = viewModel.signFido2Challenge(challenge, event.accountAddress)
                                    checkNotNull(viewModel.currentChallenge) { "Failed to sign registration challenge" }
                                    val response =
                                        credentialManager.createCredential(
                                            context = activity,
                                            request = CreatePublicKeyCredentialRequest(event.requestJson),
                                        ) as? CreatePublicKeyCredentialResponse
                                            ?: error("Unexpected passkey registration response")
                                    val handler: HandleAttestationResultUseCase = KoinPlatform.getKoin().get()
                                    val result = handler(WebAuthnCredential(response.registrationResponseJson), viewModel)
                                    viewModel.handleAttestationResultFromLauncher(result, event.accountAddress)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: CreateCredentialCancellationException) {
                                    viewModel.handleAttestationResultFromLauncher(
                                        HandleAttestationResultUseCase.Result.Cancelled("Registration cancelled"),
                                        event.accountAddress,
                                    )
                                } catch (e: Exception) {
                                    viewModel.handleAttestationResultFromLauncher(
                                        HandleAttestationResultUseCase.Result.Error(credentialErrorMessage(e)),
                                        event.accountAddress,
                                    )
                                } finally {
                                    viewModel.currentChallenge = null
                                }
                            }
                    }

                    is AnswerViewModel.ViewEvent.AuthenticationSuccess -> {
                        if (credentialOperation?.isActive == true) return@collect
                        credentialOperation =
                            scope.launch {
                                try {
                                    val challenge = JavaBase64.getUrlDecoder().decode(JSONObject(event.requestJson).getString("challenge"))
                                    viewModel.currentChallenge = viewModel.signFido2Challenge(challenge, address)
                                    checkNotNull(viewModel.currentChallenge) { "Failed to sign authentication challenge" }
                                    val response =
                                        credentialManager.getCredential(
                                            context = activity,
                                            request = GetCredentialRequest(listOf(GetPublicKeyCredentialOption(event.requestJson))),
                                        )
                                    val publicKeyCredential =
                                        response.credential as? PublicKeyCredential
                                            ?: error("Unexpected passkey authentication response")
                                    val credential = WebAuthnCredential(publicKeyCredential.authenticationResponseJson)
                                    check(credential.rawId.contentEquals(JavaBase64.getUrlDecoder().decode(event.credentialId))) {
                                        "Provider returned a different passkey"
                                    }
                                    val handler: HandleAssertionResultUseCase = KoinPlatform.getKoin().get()
                                    viewModel.handleAssertionResultFromLauncher(handler(credential, viewModel))
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: GetCredentialCancellationException) {
                                    viewModel.handleAssertionResultFromLauncher(
                                        HandleAssertionResultUseCase.Result.Cancelled("Authentication cancelled"),
                                    )
                                } catch (e: Exception) {
                                    viewModel.handleAssertionResultFromLauncher(
                                        HandleAssertionResultUseCase.Result.Error(credentialErrorMessage(e)),
                                    )
                                } finally {
                                    viewModel.currentChallenge = null
                                }
                            }
                    }

                    else -> { /* other events */ }
                }
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                viewModel.stopMppPaymentViewer()
                viewModel.unbindSignalService(context)
                viewModelStoreOwner.viewModelStore.clear()
                AnswerScreenState.isVisible = false
            }
        }

        DisposableEffect(Unit) {
            ConnectionStatusState.onDisconnect = {
                viewModel.stopMppPaymentViewer()
                viewModel.unbindSignalService(context)
                viewModelStoreOwner.viewModelStore.clear()
                AnswerScreenState.isVisible = false
                ConnectionStatusState.isVisible = false
                ConnectionStatusState.isExpanded = false
                ConnectionStatusState.session = ""
                ConnectionStatusState.origin = ""
                ConnectionStatusState.requestId = ""
                ConnectionStatusState.accountAddress = ""
            }
            onDispose {
                ConnectionStatusState.onDisconnect = null
            }
        }

        val showDialog by viewModel.showConfirmationDialog.collectAsState()
        val pendingParams by viewModel.pendingSignTransactionsParams.collectAsState()
        val pendingMessage by viewModel.pendingSignMessage.collectAsState()
        val authMessage by viewModel.authMessage.collectAsState()
        val session by viewModel.session.collectAsState()
        val accountBalance by viewModel.accountBalance.collectAsState()

        LaunchedEffect(session, authMessage, address) {
            ConnectionStatusState.isVisible = AnswerScreenState.isVisible
            ConnectionStatusState.session = session
            ConnectionStatusState.origin = authMessage?.origin ?: ""
            ConnectionStatusState.requestId = authMessage?.requestId ?: ""
            ConnectionStatusState.accountAddress = address
        }

        LaunchedEffect(AnswerScreenState.isVisible) {
            ConnectionStatusState.isVisible = AnswerScreenState.isVisible
            if (!AnswerScreenState.isVisible) {
                ConnectionStatusState.session = ""
                ConnectionStatusState.origin = ""
                ConnectionStatusState.requestId = ""
                ConnectionStatusState.accountAddress = ""
            }
        }

        AlgoKitTheme {
            Box {
                if (authMessage?.appId == AppId.LIQUID_AUTH_STREAM.name) {
                    AnswerScreen(
                        viewModel = viewModel,
                        onMinimizeToPip = {
                            // Mini player is now shown by AnswerScreen internally on minimize.
                        },
                        onViewerClose = {
                            AnswerScreenState.isVisible = false
                        },
                        onViewerTopUpConfirm = { enteredAmount ->
                            scope.launch {
                                topUpViewerSessionVault(
                                    context = context,
                                    viewModel = viewModel,
                                    viewerAddress = address,
                                    enteredAmount = enteredAmount,
                                )
                            }
                        },
                    )
                } else {
                    val params = pendingParams
                    val message = pendingMessage
                    if (showDialog && params != null && message != null) {
                        LaunchedEffect(Unit) {
                            viewModel.fetchAccountBalance()
                        }
                        val fee by produceState("") { value = viewModel.getFee() }
                        ConfirmTransferScreen(
                            provider = authMessage?.requestId ?: "",
                            origin = authMessage?.origin ?: "",
                            session = session,
                            fee = fee,
                            accountBalance = accountBalance ?: "Loading...",
                            address = address,
                            onTransactionClick = {
                                scope.launch {
                                    viewModel.processBiometricTransactionSigning(
                                        activity = activity,
                                        params = params,
                                        message = message,
                                    )
                                    viewModel.clearPendingSignRequest()
                                }
                            },
                            onClose = {
                                viewModel.clearPendingSignRequest()
                                Toast.makeText(context, "Transaction signing cancelled", Toast.LENGTH_SHORT).show()
                            },
                        )
                    }
                }
            }
        }
    }
}

private suspend fun handleWebRTCSetup(
    viewModel: AnswerViewModel,
    activity: AppCompatActivity,
    address: String,
    credential: WebAuthnCredential,
) {
    val msg = viewModel.authMessage.value ?: return
    if (viewModel.signalService.value != null) {
        Napier.d("Setting up WebRTC connection...", tag = TAG)
        // Streaming sessions receive the creator's camera + microphone as native WebRTC
        // media tracks, so request recv-only media on the offer for those sessions only.
        val enableMedia = msg.appId == AppId.LIQUID_AUTH_STREAM.name
        viewModel.signalService.value?.peer(msg.requestId, "answer", IceServerConfig.iceServers, enableMedia)
        var viewerSetupDone = false
        var credentialSent = false

        // Detect and keep publishing the viewer's own ICE connection type (LOCAL/STUN/RELAY),
        // mirroring the host side, so the viewer's "Connected Viewers" analytics card shows the
        // real network type instead of always defaulting to UNKNOWN. Polling lives in
        // LiquidAuthPlatformServices (shared with signalService lifecycle) and is stopped
        // automatically by unbindSignalService.
        viewModel.startViewerConnectionTypePolling()

        fun sendCredentialWhenOpen() {
            val service = viewModel.signalService.value ?: return
            if (credentialSent || service.dataChannel?.state() != org.webrtc.DataChannel.State.OPEN) return
            credentialSent = true
            service.send(viewModel.getCredentialMessage(address, credential).toString())
        }
        viewModel.signalService.value?.handleMessages(
            activity = activity,
            onMessage = { peerMsg ->
                if (!viewerSetupDone) {
                    viewModel.setupMppPaymentViewer(viewerAddress = address)
                    viewModel.startViewerOnChainRefresh(viewerAddress = address)
                    viewerSetupDone = true
                }
                viewModel.handleMessages(
                    msgStr = peerMsg,
                    onVideoFrame = { frameData: VideoFrameData -> viewModel.setVideoFrame(frameData) },
                )
            },
            onStateChange = { state ->
                if (state == "OPEN") {
                    sendCredentialWhenOpen()
                }
            },
            notificationBuilder = viewModel.createNotificationBuilder(activity),
            notificationId = AnswerViewModel.SERVICE_NOTIFICATION_ID,
            activityClass = null,
        )
        // OPEN can occur between applying the answer and attaching the observer.
        sendCredentialWhenOpen()
    } else {
        Toast.makeText(activity, "Couldn't find service", Toast.LENGTH_LONG).show()
    }
}

private fun credentialErrorMessage(error: Exception): String =
    when (error) {
        is NoCredentialException ->
            "No matching passkey is available. Enable the provider holding this passkey in Android settings. " +
                "Legacy Google-held credentials may not be available on AOSP; your saved credential has not been deleted."
        is CreateCredentialProviderConfigurationException,
        is GetCredentialProviderConfigurationException,
        -> "Enable a passkey provider in Android's Passwords & accounts settings, then retry."
        else -> error.message ?: "Passkey operation failed. Check your passkey provider and try again."
    }

private suspend fun topUpViewerSessionVault(
    context: Context,
    viewModel: AnswerViewModel,
    viewerAddress: String,
    enteredAmount: String,
) {
    if (viewerAddress.isBlank()) {
        Toast.makeText(context, "No viewer account selected", Toast.LENGTH_LONG).show()
        return
    }

    val signer = viewModel.buildMppWalletSigner(viewerAddress)
    if (signer == null) {
        Toast.makeText(context, "Failed to build wallet signer", Toast.LENGTH_LONG).show()
        return
    }

    viewModel
        .topUpViewerSessionVault(
            enteredAmount = enteredAmount,
            viewerAddress = viewerAddress,
            signer = signer,
        ).onSuccess { remaining ->
            if (remaining != null) {
                Toast.makeText(context, "SessionVault topped up successfully", Toast.LENGTH_SHORT).show()
                viewModel.startViewerOnChainRefresh(viewerAddress = viewerAddress)
            } else {
                Toast
                    .makeText(
                        context,
                        "Top-up submitted, but balance refresh is pending",
                        Toast.LENGTH_LONG,
                    ).show()
            }
        }.onFailure { throwable ->
            Toast
                .makeText(
                    context,
                    "Top-up failed: ${throwable.message}",
                    Toast.LENGTH_LONG,
                ).show()
        }
}
