package com.michaeltchuang.walletsdk.ui.liquidAuth.screens

import android.app.NotificationManager
import android.content.Context
import android.os.StrictMode
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.google.android.gms.fido.fido2.Fido2ApiClient
import com.google.android.gms.fido.fido2.api.common.PublicKeyCredential
import com.michaeltchuang.walletsdk.core.foundation.utils.AppId
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.json.JSONObject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.mp.KoinPlatform
import java.security.Security
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

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
        var connectedHostAddress by remember { mutableStateOf("") }

        val fido2Client = remember { Fido2ApiClient(activity) }

        val attestationLauncher =
            rememberLauncherForActivityResult(
                ActivityResultContracts.StartIntentSenderForResult(),
            ) { activityResult ->
                scope.launch {
                    val useCase =
                        HandleAttestationResultUseCase(
                            attestationApiUseCase = KoinPlatform.getKoin().get(),
                        )
                    val result = useCase(activityResult, viewModel)
                    viewModel.handleAttestationResultFromLauncher(result, address)
                }
            }

        val assertionLauncher =
            rememberLauncherForActivityResult(
                ActivityResultContracts.StartIntentSenderForResult(),
            ) { activityResult ->
                scope.launch {
                    val useCase =
                        HandleAssertionResultUseCase(
                            assertionApiUseCase = KoinPlatform.getKoin().get(),
                        )
                    val result = useCase(activityResult, viewModel)
                    viewModel.handleAssertionResultFromLauncher(result)
                }
            }

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
                viewModel.signalService.collect { service ->
                    if (service == null) return@collect

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
                    // Only run once — stop collecting after the first non-null service.
                    return@collect
                }
            }
        }

        LaunchedEffect(viewModel) {
            viewModel.viewEvent.collect { event ->
                when (event) {
                    is AnswerViewModel.ViewEvent.AttestationSuccess -> {
                        Log.d(TAG, "Attestation Success - setting up WebRTC")
                        viewModel.setSession(LIQUID_AUTH_SESSION)
                        handleWebRTCSetup(
                            viewModel = viewModel,
                            activity = activity,
                            address = address,
                            credential = event.credential,
                            onHostAddressChanged = { connectedHostAddress = it },
                        )
                    }

                    is AnswerViewModel.ViewEvent.AssertionSuccess -> {
                        Log.d(TAG, "Assertion Success - setting up WebRTC")
                        viewModel.authMessage.value?.let { _ ->
                            viewModel.setSession(LIQUID_AUTH_SESSION)
                        }
                        handleWebRTCSetup(
                            viewModel = viewModel,
                            activity = activity,
                            address = address,
                            credential = event.credential,
                            onHostAddressChanged = { connectedHostAddress = it },
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
                        val challenge = event.pubKeyCredentialCreationOptions.challenge
                        val signature = viewModel.signFido2Challenge(challenge, address)
                        if (signature != null) {
                            viewModel.currentChallenge = signature
                            try {
                                val pendingIntent =
                                    fido2Client
                                        .getRegisterPendingIntent(
                                            event.pubKeyCredentialCreationOptions,
                                        ).await()
                                attestationLauncher.launch(
                                    IntentSenderRequest.Builder(pendingIntent).build(),
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to launch registration intent", e)
                                Toast.makeText(context, "Failed to launch passkey registration", Toast.LENGTH_LONG).show()
                            }
                        } else {
                            Toast.makeText(context, "Failed to sign FIDO2 challenge", Toast.LENGTH_LONG).show()
                        }
                    }

                    is AnswerViewModel.ViewEvent.AuthenticationSuccess -> {
                        val challenge = event.publicKeyCredentialRequestOptions.challenge
                        val signature = viewModel.signFido2Challenge(challenge, address)
                        if (signature != null) {
                            viewModel.currentChallenge = signature
                            try {
                                val pendingIntent =
                                    fido2Client
                                        .getSignPendingIntent(
                                            event.publicKeyCredentialRequestOptions,
                                        ).await()
                                assertionLauncher.launch(
                                    IntentSenderRequest.Builder(pendingIntent).build(),
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to launch assertion intent", e)
                                Toast.makeText(context, "Failed to launch passkey authentication", Toast.LENGTH_LONG).show()
                            }
                        } else {
                            Toast.makeText(context, "Failed to sign assertion challenge", Toast.LENGTH_LONG).show()
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
                                    hostAddress = connectedHostAddress,
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
    credential: PublicKeyCredential,
    onHostAddressChanged: (String) -> Unit,
) {
    val msg = viewModel.authMessage.value ?: return
    if (viewModel.signalService.value != null) {
        Log.d(TAG, "Setting up WebRTC connection...")
        viewModel.signalService.value?.peer(msg.requestId, "answer", IceServerConfig.iceServers)
        var hasStartedMppViewer = false
        viewModel.signalService.value?.handleMessages(
            activity = activity,
            onMessage = { peerMsg ->
                // Cache host / pay-to address from incoming messages for top-up flows.
                extractHostAddress(peerMsg)?.let { host ->
                    if (host.isNotBlank()) {
                        onHostAddressChanged(host)
                        if (!hasStartedMppViewer) {
                            hasStartedMppViewer = true
                            viewModel.setupMppPaymentViewer(viewerAddress = address, hostAddress = host)
                            viewModel.startViewerOnChainRefresh(address, host)
                        }
                    }
                }
                viewModel.handleMessages(
                    msgStr = peerMsg,
                    onVideoFrame = { frameData: VideoFrameData -> viewModel.setVideoFrame(frameData) },
                )
            },
            onStateChange = { state ->
                if (state == "OPEN") {
                    val credentialMessage = viewModel.getCredentialMessage(address, credential).toString()
                    viewModel.signalService.value?.send(credentialMessage)
                }
            },
            notificationBuilder = viewModel.createNotificationBuilder(activity),
            notificationId = AnswerViewModel.SERVICE_NOTIFICATION_ID,
            activityClass = null,
        )
    } else {
        Toast.makeText(activity, "Couldn't find service", Toast.LENGTH_LONG).show()
    }
}

private suspend fun topUpViewerSessionVault(
    context: Context,
    viewModel: AnswerViewModel,
    viewerAddress: String,
    hostAddress: String,
    enteredAmount: String,
) {
    if (viewerAddress.isBlank()) {
        Toast.makeText(context, "No viewer account selected", Toast.LENGTH_LONG).show()
        return
    }
    if (hostAddress.isBlank()) {
        Toast
            .makeText(
                context,
                "Missing stream recipient. Please wait for payment request and try again.",
                Toast.LENGTH_LONG,
            ).show()
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
            creatorAddress = hostAddress,
            signer = signer,
        ).onSuccess { remaining ->
            if (remaining != null) {
                Toast.makeText(context, "SessionVault topped up successfully", Toast.LENGTH_SHORT).show()
                viewModel.startViewerOnChainRefresh(viewerAddress, hostAddress)
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

private fun extractHostAddress(msgStr: String): String? {
    return runCatching {
        val json = JSONObject(msgStr)

        fun extractHost(obj: JSONObject?): String? {
            if (obj == null) return null
            return listOf("hostAddress", "payTo", "recipient", "to", "address")
                .firstNotNullOfOrNull { key ->
                    obj.optString(key, "").takeIf { it.isNotBlank() }
                }
        }
        extractHost(json)
            ?: extractHost(json.optJSONObject("request"))
            ?: extractHost(json.optJSONObject("params"))
            ?: extractHost(json.optJSONObject("methodDetails"))
    }.getOrNull()
}
