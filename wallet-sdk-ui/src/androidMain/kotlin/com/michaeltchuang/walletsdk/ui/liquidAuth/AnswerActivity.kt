package com.michaeltchuang.walletsdk.ui.liquidAuth

import android.app.AlertDialog
import android.app.NotificationManager
import android.app.PictureInPictureParams
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.StrictMode
import android.util.Log
import android.util.Rational
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.algorand.algosdk.crypto.Address
import com.algorand.algosdk.util.Encoder
import com.google.android.gms.fido.fido2.Fido2ApiClient
import com.google.android.gms.fido.fido2.api.common.PublicKeyCredential
import com.google.android.gms.fido.fido2.api.common.PublicKeyCredentialCreationOptions
import com.algorand.algosdk.sdk.Sdk
import com.michaeltchuang.walletsdk.core.algosdk.signAlgo25ArbitraryData
import com.michaeltchuang.walletsdk.core.deeplink.utils.AssetConstants.USDC_TESTNET_ID
import com.michaeltchuang.walletsdk.core.liquidAuth.auth.connect.AuthMessage
import com.michaeltchuang.walletsdk.core.liquidAuth.auth.connect.SignalClient
import com.michaeltchuang.walletsdk.core.railmpp.LiquidStreamViewer
import com.michaeltchuang.walletsdk.core.railmpp.MppClientConfig
import com.michaeltchuang.walletsdk.core.railmpp.MppNetworks
import com.michaeltchuang.walletsdk.core.railmpp.MppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.core.BudgetCap
import com.michaeltchuang.walletsdk.core.railmpp.core.ClientConfig
import com.michaeltchuang.walletsdk.core.railmpp.core.ConsentApproval
import com.michaeltchuang.walletsdk.core.railmpp.core.ConsentHandler
import com.michaeltchuang.walletsdk.core.railmpp.core.ConsentTerms
import com.michaeltchuang.walletsdk.core.railmpp.core.PAYMENT_CHANNEL_LABEL
import com.michaeltchuang.walletsdk.core.railmpp.utils.MppPayments
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.liquidAuth.AnswerViewModel.Companion.SERVICE_NOTIFICATION_ID
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases.HandleAssertionResultUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.screens.AnswerScreen
import com.michaeltchuang.walletsdk.ui.liquidAuth.screens.ConfirmTransferScreen
import com.solanamobile.seedvault.Wallet
import com.solanamobile.seedvault.WalletContractV1
import foundation.algorand.provider.Message
import foundation.algorand.provider.avm.models.ResponseMessage
import foundation.algorand.provider.avm.models.SignTransactionsParams
import foundation.algorand.provider.avm.models.SignTransactionsResult
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.json.JSONObject
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.webrtc.DataChannel
import java.security.Security
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlinx.coroutines.isActive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class AnswerActivity : AppCompatActivity() {
    private var shouldEnterPipMode = false

    companion object {
        private const val TAG = "AnswerActivity"
        const val EXTRA_ACCOUNT_ADDRESS = "EXTRA_ACCOUNT_ADDRESS"
        const val EXTRA_ALGO_ADDRESS = EXTRA_ACCOUNT_ADDRESS // backward-compatible alias
    }

    private val viewModel: AnswerViewModel by viewModel()

    // FIDO/Auth interfaces
    private var fido2Client: Fido2ApiClient? = null
    private var signalClient: SignalClient? = null

    // State variables - cleaned up and consolidated
    private var algoAddress: String? = null
    private var mnemonic: String? = null

    // Authenticate/Assertion Intent Channel
    private lateinit var assertionIntentLauncher: ActivityResultLauncher<IntentSenderRequest>

    private lateinit var attestationIntentLauncher: ActivityResultLauncher<IntentSenderRequest>
    private lateinit var seedVaultSignMessageLauncher: ActivityResultLauncher<Intent>
    private var pendingSeedVaultSignContinuation: CancellableContinuation<ByteArray?>? = null
    private var liquidStreamViewer: LiquidStreamViewer? = null
    private var viewerOnChainRefreshJob: Job? = null
    private var connectedHostAddress: String? = null
    private var connectedPaymentSessionId: String? = null
    private var viewerBlocksConsumedByRefresh: Int = 0
    private var viewerLastObservedBlockNumber: Long? = null
    private var viewerLastVoucherSentBlocks: Int = 0
    private var viewerSessionStartRemainingMicroUsdc: Long? = null

    private data class SeedVaultSigner(
        val authToken: Long,
        val derivationPath: Uri,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupUi()
        setupViewModelObservers()
        viewModel.bindSignalService(this)
        setupAccountAndCredentials()
        setupSecurityProviders()
        setupFidoClient()
        viewModel.logAppSignature(this)
        attestationIntentLauncher =
            viewModel.getAttestationIntentLauncher(this) { result ->
                viewModel.handleAttestationResultFromLauncher(result, algoAddress)
            }
        assertionIntentLauncher =
            viewModel.getAssertionIntentLauncher(this) { result ->
                handleAuthenticatorAssertionResultCallback(result)
            }

        seedVaultSignMessageLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                val continuation = pendingSeedVaultSignContinuation
                pendingSeedVaultSignContinuation = null

                if (continuation == null || !continuation.isActive) return@registerForActivityResult

                try {
                    val signingResponses = Wallet.onSignMessagesResult(result.resultCode, result.data)
                    val signature = signingResponses.firstOrNull()?.signatures?.firstOrNull()
                    continuation.resume(signature)
                } catch (e: Exception) {
                    Log.e(TAG, "Seed Vault message signing failed", e)
                    continuation.resume(null)
                }
            }
    }

    private fun setupUi() {
        setContent {
            AlgoKitTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = AlgoKitTheme.colors.background,
                ) {
                    val scope = rememberCoroutineScope()
                    val showDialogState = viewModel.showConfirmationDialog.collectAsState()
                    val authMessage = viewModel.authMessage.collectAsState().value
                    val session = viewModel.session.collectAsState().value
                    val accountBalance = viewModel.accountBalance.collectAsState().value
                    val pendingParams = viewModel.pendingSignTransactionsParams.collectAsState().value
                    val pendingMessage = viewModel.pendingSignMessage.collectAsState().value
                    AnswerScreen(
                        viewModel = viewModel,
                        onMinimizeToPip = { enterPipMode() },
                    )
                    if (showDialogState.value && pendingParams != null && pendingMessage != null) {
                        LaunchedEffect(Unit) {
                            viewModel.fetchAccountBalance()
                        }
                        val feeState =
                            produceState("") {
                                value = viewModel.getFee()
                            }
                        ConfirmTransferScreen(
                            provider = authMessage?.requestId ?: "",
                            origin = authMessage?.origin ?: "",
                            session = session,
                            fee = feeState.value,
                            accountBalance = accountBalance ?: "Loading...",
                            address = algoAddress!!,
                            onTransactionClick = {
                                scope.launch {
                                    processTransactionSigning(pendingParams, pendingMessage)
                                    viewModel.clearPendingSignRequest()
                                }
                            },
                            onClose = {
                                viewModel.clearPendingSignRequest()
                                showToast("Transaction signing cancelled")
                            },
                        )
                    }
                }
            }
        }
    }

    private fun setupViewModelObservers() {
        lifecycleScope.launch {
            viewModel.viewEvent.collect { event ->
                when (event) {
                    is AnswerViewModel.ViewEvent.AttestationSuccess -> {
                        Log.d(TAG, "✅ Attestation Success - setting up WebRTC")
                        // Update session to show connected state
                        setSession("Connected") // Update session to stop spinner
                        handleWebRTCSetup(event.credential)
                    }

                    is AnswerViewModel.ViewEvent.AttestationCancelled -> {
                        showToast("Attestation cancelled.")
                    }

                    is AnswerViewModel.ViewEvent.AttestationError -> {
                        showToast("Attestation failed: ${event.message}")
                    }

                    is AnswerViewModel.ViewEvent.ShowToast -> {
                        showToast(event.message)
                    }

                    is AnswerViewModel.ViewEvent.ShowError -> {
                        showToast(event.message, Toast.LENGTH_LONG)
                    }

                    is AnswerViewModel.ViewEvent.StreamDisconnected -> {
                        showToast(event.reason, Toast.LENGTH_LONG)
                        finish()
                    }

                    is AnswerViewModel.ViewEvent.TransactionSigned -> {
                        Log.d(TAG, "✅ Transaction signed event received")
                        sendSignedTransactions(event.resultMessage, event.signResult)
                    }

                    is AnswerViewModel.ViewEvent.AssertionSuccess -> {
                        Log.d(TAG, "✅ Assertion Success - setting up WebRTC")
                        // Update session to show connected state
                        viewModel.authMessage.value?.let { msg ->
                            setSession("Connected") // Update session to stop spinner
                        }
                        lifecycleScope.launch {
                            handleWebRTCSetup(event.credential)
                        }
                    }

                    is AnswerViewModel.ViewEvent.RegistrationSuccess -> {
                        lifecycleScope.launch {
                            signChallengeAndLaunchRegistration(
                                event.pubKeyCredentialCreationOptions,
                                event.accountAddress,
                            )
                        }
                    }

                    is AnswerViewModel.ViewEvent.AuthenticationSuccess -> {
                        lifecycleScope.launch {
                            val accountAddress = algoAddress
                            if (accountAddress == null) {
                                showToast("Missing account address for authentication", Toast.LENGTH_LONG)
                                return@launch
                            }

                            val assertionChallengeSignature =
                                signChallengeForAccount(
                                    challenge = event.publicKeyCredentialRequestOptions.challenge,
                                    accountAddress = accountAddress,
                                )

                            if (assertionChallengeSignature == null) {
                                showToast("Failed to sign assertion challenge", Toast.LENGTH_LONG)
                                return@launch
                            }

                            viewModel.currentChallenge = assertionChallengeSignature

                            val pendingIntent =
                                fido2Client!!
                                    .getSignPendingIntent(
                                        event.publicKeyCredentialRequestOptions,
                                    ).await()
                            assertionIntentLauncher.launch(
                                IntentSenderRequest.Builder(pendingIntent).build(),
                            )
                        }
                    }

                    else -> {
                        // Handle other events (VideoFrameReceived is handled via direct callback)
                    }
                }
            }
        }
    }

    private fun setupAccountAndCredentials() {
        algoAddress =
            intent.getStringExtra(EXTRA_ACCOUNT_ADDRESS)
                ?: intent.getStringExtra(EXTRA_ALGO_ADDRESS)
        lifecycleScope.launch {
            algoAddress?.let {
                mnemonic = viewModel.getMnemonic(it)
                viewModel.setAccountAddress(it)
                val clearCredentialsOnStart = false
                if (clearCredentialsOnStart) {
                    Log.w(TAG, "🧪 TEST MODE: Clearing all stored credentials for $it")
                    viewModel.deleteCredentialByAccountAddress(it)
                }
            }
        }
    }

    private suspend fun ensureAccountSelected(): String? {
        algoAddress?.let { return it }

        val addresses = viewModel.getAvailableAccountAddresses()
        if (addresses.isEmpty()) {
            showToast("No local accounts found. Please create or import an account first.", Toast.LENGTH_LONG)
            return null
        }

        return suspendCancellableCoroutine { continuation ->
            runOnUiThread {
                AlertDialog
                    .Builder(this)
                    .setTitle("Select account")
                    .setItems(addresses.toTypedArray()) { _, which ->
                        val selectedAddress = addresses[which]
                        algoAddress = selectedAddress
                        viewModel.setAccountAddress(selectedAddress)
                        lifecycleScope.launch {
                            mnemonic = viewModel.getMnemonic(selectedAddress)
                        }
                        if (continuation.isActive) {
                            continuation.resume(selectedAddress)
                        }
                    }.setOnCancelListener {
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                    }.show()
            }
        }
    }

    private fun setupSecurityProviders() {
        val policy =
            StrictMode.ThreadPolicy
                .Builder()
                .permitAll()
                .build()
        StrictMode.setThreadPolicy(policy)
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 0)
    }

    private fun setupFidoClient() {
        fido2Client = Fido2ApiClient(this@AnswerActivity)
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        initWebRTCService { hydrateIntents() }

        // Avoid re-auth loops when returning from PiP/background with active session.
        if (isViewerSessionActive()) {
            Log.d(TAG, "Session already active, skipping auto-connect/auth flow")
            return
        }

        // Only auto-connect if we don't have an intent (deep link or QR scan)
        val hasIntent = intent?.data != null
        val hasValidAuthMessage =
            AuthMessageStorage.authMessage.origin.isNotEmpty() &&
                AuthMessageStorage.authMessage.requestId.isNotEmpty()

        if (!hasIntent && hasValidAuthMessage) {
            Log.d(TAG, "No intent detected, auto-connecting with stored AuthMessage")
            Handler().postDelayed({
                if (!isViewerSessionActive()) {
                    connect(AuthMessageStorage.authMessage)
                } else {
                    Log.d(TAG, "Skipping delayed auto-connect; session became active")
                }
            }, 2000)
        } else {
            if (!hasValidAuthMessage) {
                Log.d(TAG, "No valid AuthMessage stored, skipping auto-connect")
            } else {
                Log.d(TAG, "Intent detected, skipping auto-connect (will use scanned QR data)")
            }
        }
    }

    private fun isViewerSessionActive(): Boolean {
        val dataChannelState =
            viewModel.signalService.value
                ?.dataChannel
                ?.state()
        val hasOpenDataChannel = dataChannelState == DataChannel.State.OPEN
        val hasConnectedSession = viewModel.session.value == "Connected"
        return hasOpenDataChannel || hasConnectedSession
    }

    /** Reload the application state from an Intent */
    private fun hydrateIntents() {
        val isConnected =
            viewModel.signalService.value?.dataChannel is DataChannel &&
                viewModel.signalService.value
                    ?.dataChannel
                    ?.state() === DataChannel.State.OPEN
        val isIntent = intent != null
        val isDeepLink = intent?.data != null && intent.data is Uri
        val isDataChannelMessage = intent?.getStringExtra("msg") != null
        if (isDeepLink) {
            if (isViewerSessionActive()) {
                Log.d(TAG, "Ignoring deep-link auth bootstrap because viewer session is already active")
                return
            }
            viewModel.signalService.value?.updateDeepLinkFlag(true)
            val intentUri = intent.data as Uri
            Log.d(TAG, "========================================")
            Log.d(TAG, "🔍 DEEP LINK DETECTED")
            Log.d(TAG, "Intent URI: $intentUri")
            Log.d(TAG, "Intent URI scheme: ${intentUri.scheme}")
            Log.d(TAG, "Intent URI host: ${intentUri.host}")
            Log.d(TAG, "========================================")

            // Find the Application ID in the Intent Extras
            if (intent.extras is Bundle) {
                val bundle = intent.extras as Bundle
                val keySet = bundle.keySet().toTypedArray()
                for (k in keySet) {
                    if (k.contains("application_id")) {
                        bundle.getString(k)?.let { appId ->
                            viewModel.signalService.value?.updateLastKnownReferer(appId)
                        }
                    }
                }
            }
            // Find the Referrer in the Activity
            this@AnswerActivity.referrer?.let {
                Log.d(TAG, "Referrer: $it")
                viewModel.signalService.value?.updateLastKnownReferer(it.toString())
            }

            // Set the Message and Start the Service
            val msg =
                try {
                    AuthMessage.fromUri(intentUri)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse AuthMessage from URI: $intentUri", e)
                    viewModel.setError("Invalid QR code format: ${e.message}")
                    Toast
                        .makeText(
                            this@AnswerActivity,
                            "Invalid QR code format: ${e.message}",
                            Toast.LENGTH_LONG,
                        ).show()
                    return
                }

            Log.d(TAG, "========================================")
            Log.d(TAG, "✅ PARSED AUTH MESSAGE FROM QR CODE")
            Log.d(TAG, "Origin: ${msg.origin}")
            Log.d(TAG, "RequestID: ${msg.requestId}")
            Log.d(TAG, "========================================")

            viewModel.setMessage(msg)
            viewModel.clearError()
            viewModel.signalService.value?.start(
                msg.origin,
                viewModel.getProvideHttpClient(),
                viewModel.createNotificationBuilder(this@AnswerActivity),
                SERVICE_NOTIFICATION_ID,
                AnswerActivity::class.java,
            )

            // Launch the authentication process
            lifecycleScope.launch {
                val selectedAddress = ensureAccountSelected() ?: return@launch
                val savedCredential = viewModel.getCredentialIdByAccountAddress(selectedAddress)
                if (savedCredential === null) {
                    register(msg)
                } else {
                    authenticate(msg, savedCredential)
                }
            }
        }

        // Handle a datachannel message
        if (isDataChannelMessage) {
            val msg = intent.getStringExtra("msg")
            if (msg !== null) {
                handleMessages(msg)
            }
        }
    }

    /**
     * Initialize the WebRTC Service
     *
     * This checks for a bound service and starts the service if it is not already running.
     */
    private fun initWebRTCService(onServiceConnection: () -> Unit) {
        // Check if the service is already bound
        viewModel.createChannels(
            getSystemService(NOTIFICATION_SERVICE) as NotificationManager,
        )
    }

    /**
     * Show Transaction Signing Confirmation Dialog
     * Delegates to ViewModel for better separation of concerns
     */
    private fun showTransactionConfirmationDialog(
        params: SignTransactionsParams,
        message: Message,
    ) {
        /* viewModel.showTransactionConfirmationDialog(
             context = this@AnswerActivity,
             params = params,
             onConfirm = {
                 lifecycleScope.launch {
                     processTransactionSigning(params, message)
                 }
             },
             onCancel = {
                 showToast("Transaction signing cancelled")
             }
         )*/
    }

    /**
     * Process transaction signing with biometric authentication
     * Delegates to ViewModel - events are handled in observeViewEvents()
     */
    private suspend fun processTransactionSigning(
        params: SignTransactionsParams,
        message: Message,
    ) {
        viewModel.processBiometricTransactionSigning(
            activity = this@AnswerActivity,
            params = params,
            message = message,
        )
    }

    /**
     * Send signed transactions through data channel
     */
    @OptIn(ExperimentalEncodingApi::class)
    private fun sendSignedTransactions(
        resultMessage: ResponseMessage,
        signResult: SignTransactionsResult,
    ) {
        Log.d(TAG, "========================================")
        Log.d(TAG, "📤 SENDING SIGNED TRANSACTIONS")
        Log.d(TAG, "Number of signed txns: ${signResult.stxns.size}")
        Log.d(TAG, "Provider ID: ${signResult.providerId}")
        Log.d(TAG, "Response ID: ${resultMessage.id}")
        Log.d(TAG, "========================================")

        // Encode using CBOR
        val cborBytes = viewModel.encodeResponseMessage(resultMessage)
        val base64String = Base64.UrlSafe.encode(cborBytes)
        Log.d(TAG, "CBOR response length: ${cborBytes.size} bytes")
        Log.d(TAG, "Base64 encoded length: ${base64String.length} chars")

        // Log first bytes to verify CBOR encoding type (definite vs indefinite)
        // Definite-length: 0xA0-0xB7 (map), 0x80-0x97 (array), 0x40-0x57 (bytes), 0x60-0x77 (text)
        // Indefinite-length: 0xBF (map), 0x9F (array), 0x5F (bytes), 0x7F (text) + 0xFF break
        if (cborBytes.isNotEmpty()) {
            val firstBytes = cborBytes.take(10).joinToString(" ") { "0x%02X".format(it) }
            Log.d(TAG, "CBOR first bytes: $firstBytes")
            Log.d(
                TAG,
                "CBOR encoding: ${if (cborBytes[0].toInt() and 0x1F == 0x1F) "INDEFINITE-LENGTH (needs 0xFF break)" else "DEFINITE-LENGTH"}",
            )
        }

        viewModel.signalService.value?.send(base64String)

        Log.d(TAG, "✅ Signed transactions sent successfully as CBOR!")
        showToast("Transactions signed successfully!")
    }

    /**
     * Helper to show toast messages
     */
    private fun showToast(
        message: String,
        length: Int = Toast.LENGTH_SHORT,
    ) {
        runOnUiThread {
            Toast.makeText(this@AnswerActivity, message, length).show()
        }
    }

    /**
     * Handle Messages
     *
     * Callback for datachannel messages
     */
    @OptIn(ExperimentalEncodingApi::class)
    private fun handleMessages(msgStr: String) {
        cacheConnectedHostAddressFromSignalMessage(msgStr)

        // Use ViewModel's handleMessages method
        viewModel.handleMessages(
            msgStr = msgStr,
            onVideoFrame = { frameData ->
                // Update video frame directly in ViewModel
                viewModel.setVideoFrame(frameData)
            },
        )
    }

    private fun cacheConnectedHostAddressFromSignalMessage(msgStr: String) {
        runCatching {
            val json = JSONObject(msgStr)

            fun extractHost(obj: JSONObject?): String? {
                if (obj == null) return null
                return listOf("hostAddress", "payTo", "recipient", "to", "address")
                    .firstNotNullOfOrNull { key -> obj.optString(key, "").takeIf { it.isNotBlank() } }
            }

            val direct = extractHost(json)
            val nestedRequest = extractHost(json.optJSONObject("request"))
            val nestedParams = extractHost(json.optJSONObject("params"))
            val nestedMethodDetails = extractHost(json.optJSONObject("methodDetails"))
            val candidate = direct ?: nestedRequest ?: nestedParams ?: nestedMethodDetails

            val sessionCandidate =
                listOf(
                    json.optString("sessionId", ""),
                    json.optString("id", ""),
                    json.optJSONObject("request")?.optString("id", "") ?: "",
                ).firstOrNull { it.isNotBlank() && it != "Connected" }

            if (!sessionCandidate.isNullOrBlank()) {
                val sessionChanged = connectedPaymentSessionId != sessionCandidate
                connectedPaymentSessionId = sessionCandidate
                if (sessionChanged) {
                    viewerBlocksConsumedByRefresh = 0
                    viewerLastVoucherSentBlocks = 0
                    viewerSessionStartRemainingMicroUsdc = null
                    Log.e(TAG, "[VIEWER_PAYMENT_SESSION_CACHED] source=signal_message session=$sessionCandidate")
                }
            }

            if (!candidate.isNullOrBlank()) {
                val changed = connectedHostAddress != candidate
                connectedHostAddress = candidate
                if (changed) {
                    Log.e(TAG, "[VIEWER_HOST_ADDRESS_CACHED] source=signal_message host=$candidate")
                }

                val viewerAddress = algoAddress.orEmpty()
                if (viewerAddress.isNotBlank() && (changed || viewerOnChainRefreshJob == null)) {
                    Log.e(
                        TAG,
                        "[VIEWER_SESSION_VAULT_REFRESH_KICK] source=signal_host_cache viewer=$viewerAddress host=$candidate session=${connectedPaymentSessionId.orEmpty()}",
                    )
                    startViewerOnChainRefresh(
                        viewerAddress = viewerAddress,
                        hostAddress = candidate,
                    )
                }
            }
        }.onFailure { e ->
            Log.e(TAG, "[VIEWER_HOST_ADDRESS_CACHE_ERR] ${e.message}", e)
        }
    }

    /**
     * Connect/Proof of Knowledge API
     *
     * Connects the Wallet/Android Application to a dApp/website using a Barcode. The barcode must
     * use the liquid uri scheme and contain a request id.
     *
     * liquid://<ORIGIN>/?requestId=<REQUEST_ID>
     *
     * In Android 14, the application can handle the FIDO:/ URI scheme directly. This is useful when
     * a user is registering the phone as an Authenticator for the first time.
     */
    private fun connect(msg: AuthMessage) {
        Log.d(TAG, "========================================")
        Log.d(TAG, "🔗 CONNECT() CALLED (Delayed Auto-Connect)")
        Log.d(TAG, "Origin: ${msg.origin}")
        Log.d(TAG, "RequestID: ${msg.requestId}")
        Log.d(TAG, "========================================")

        if (isViewerSessionActive()) {
            Log.d(TAG, "Skipping connect(); viewer session already active")
            return
        }

        // Check if message is empty/invalid
        if (msg.origin.isEmpty() || msg.requestId.isEmpty()) {
            Log.w(TAG, "⚠️ connect() called with empty AuthMessage, skipping")
            return
        }

        // Decode Barcode Message
        viewModel.setMessage(msg)
        viewModel.clearError()
        viewModel.signalService.value?.updateDeepLinkFlag(false)
        viewModel.signalService.value?.start(
            msg.origin,
            viewModel.getProvideHttpClient(),
            viewModel.createNotificationBuilder(this@AnswerActivity),
            SERVICE_NOTIFICATION_ID,
            AnswerActivity::class.java,
        )
        // Connect to Service
        lifecycleScope.launch {
            val selectedAddress = ensureAccountSelected() ?: return@launch
            val savedCredential = viewModel.getCredentialIdByAccountAddress(selectedAddress)
            signalClient =
                SignalClient(msg.origin, this@AnswerActivity, viewModel.getProvideHttpClient())
            if (savedCredential === null) {
                register(msg)
            } else {
                authenticate(msg, savedCredential)
            }
        }
    }

    /**
     * Registration of a new Credential (Step 1 of 2)
     * Delegates to use case for better separation of concerns
     */
    private fun register(
        msg: AuthMessage,
        options: JSONObject = JSONObject(),
    ) {
        viewModel.registerPasskey(
            authMessage = msg,
            accountAddress = algoAddress!!,
            options = options,
        )
    }

    /**
     * Handle WebRTC setup after successful registration
     */
    private suspend fun handleWebRTCSetup(credential: PublicKeyCredential) {
        val msg = viewModel.authMessage.value ?: return

        if (viewModel.signalService.value != null) {
            Log.d(TAG, "Setting up WebRTC connection...")
            viewModel.signalService.value?.peer(msg.requestId, "answer", IceServerConfig.iceServers)
            setupMppPaymentViewer()
            runOnUiThread {
                if (viewModel.signalService.value?.isDeepLink == true) this@AnswerActivity.onBackPressed()
            }
            viewModel.signalService.value?.handleMessages(
                this@AnswerActivity,
                { peerMsg ->
                    Log.d(TAG, "handleMessages($peerMsg)")
                    handleMessages(peerMsg)
                },
                {
                    Log.d(TAG, "onStateChange($it)")
                    if (it === "OPEN") {
                        Log.e(
                            TAG,
                            "[VIEWER_LIQUIDAUTH_CONNECTED] requestId=${msg.requestId} account=${algoAddress} dcState=OPEN",
                        )
                        Log.e(TAG, "[VIEWER_CREDENTIAL_SEND_START] requestId=${msg.requestId}")
                        val credentialMessage =
                            viewModel
                                .getCredentialMessage(
                                    algoAddress!!,
                                    credential,
                                ).toString()
                        Log.e(
                            TAG,
                            "[VIEWER_CREDENTIAL_SEND_PAYLOAD] requestId=${msg.requestId} bytes=${credentialMessage.toByteArray().size}",
                        )
                        viewModel.signalService.value?.send(credentialMessage)
                        Log.e(TAG, "[VIEWER_CREDENTIAL_SEND_DONE] requestId=${msg.requestId}")
                        Log.e(
                            TAG,
                            "[VIEWER_SESSION_VAULT_REFRESH_BOOTSTRAP] requestId=${msg.requestId} viewer=$algoAddress",
                        )
                        // Host address unknown at bootstrap; refresh starts once payTo is known.
                    }
                },
                viewModel.createNotificationBuilder(this@AnswerActivity),
                SERVICE_NOTIFICATION_ID,
                AnswerActivity::class.java,
            )
        } else {
            showToast("Couldn't find service", Toast.LENGTH_LONG)
        }
    }

    private fun setupMppPaymentViewer() {
        val service = viewModel.signalService.value ?: return
        val peerConnection = service.peerConnection ?: return
        val accountAddress = algoAddress ?: return

        lifecycleScope.launch {
            try {
                val paymentChannel = awaitPaymentDataChannel(service) ?: return@launch
                stopViewerOnChainRefresh()
                liquidStreamViewer?.terminate()
                val mppNetwork = resolveMppClientNetwork(accountAddress)
                liquidStreamViewer =
                    LiquidStreamViewer(
                        peerConnection = peerConnection,
                        dataChannel = paymentChannel,
                        mppClientConfig =
                            MppClientConfig(
                                network = mppNetwork,
                                signer = buildMppWalletSigner(accountAddress),
                            ),
                        consentHandler =
                            object : ConsentHandler {
                                override suspend fun requestConsent(terms: ConsentTerms): ConsentApproval {
                                    val hostAddress = connectedHostAddress.orEmpty()
                                    val existingOnChainBalance =
                                        if (hostAddress.isBlank()) {
                                            null
                                        } else {
                                            MppPayments.getRemainingBalanceFromSessionVault(
                                                viewerAddress = accountAddress,
                                                hostAddress = hostAddress,
                                                appId =
                                                    com.michaeltchuang.walletsdk.core.railmpp.utils
                                                        .RailMppConstants
                                                        .MPP_SESSION_VAULT_APP_ID,
                                            )
                                        }

                                    if (existingOnChainBalance != null && existingOnChainBalance > 0L) {
                                        runOnUiThread {
                                            viewModel.setViewerSessionVaultBalance(existingOnChainBalance)
                                        }
                                        Log.e(
                                            TAG,
                                            "[VIEWER_SESSION_VAULT_FUNDED] balanceMicroUsdc=$existingOnChainBalance balanceUsdc=${existingOnChainBalance / 1_000_000.0} action=skip_payment_modal",
                                        )
                                        startViewerOnChainRefresh(
                                            viewerAddress = accountAddress,
                                            hostAddress = hostAddress,
                                        )
                                        return ConsentApproval(
                                            approved = true,
                                            autoPaySegments = true,
                                            budgetCap =
                                                BudgetCap(
                                                    amount = existingOnChainBalance.toString(),
                                                    asset = USDC_TESTNET_ID.toString(),
                                                ),
                                        )
                                    }

                                    val approval = viewModel.requestMppConsentFromUi(terms)
                                    if (!approval.approved) return approval

                                    val payToAddress = connectedHostAddress.orEmpty()
                                    val depositMicroUsdc = 1_000_000L
                                    val signer = buildMppWalletSigner(accountAddress)

                                    if (payToAddress.isBlank()) {
                                        Log.e(
                                            TAG,
                                            "[VIEWER_SESSION_VAULT_DEPOSIT_SKIP] reason=missing_recipient account=$accountAddress",
                                        )
                                        return approval
                                    }

                                    val onChain =
                                        MppPayments.openSessionAndDeposit(
                                            signer = signer,
                                            viewerAddress = accountAddress,
                                            creatorAddress = payToAddress,
                                            depositAmountMicroUsdc = depositMicroUsdc,
                                        )
                                    onChain
                                        .onSuccess { txId ->
                                            Log.e(TAG, "✅ Session Vault openSession+deposit txId=$txId")
                                            val onChainRemaining =
                                                MppPayments.getRemainingBalanceFromSessionVault(
                                                    viewerAddress = accountAddress,
                                                    hostAddress = payToAddress,
                                                    appId =
                                                        com.michaeltchuang.walletsdk.core.railmpp.utils.RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                                                )
                                            Log.e(
                                                TAG,
                                                "[VIEWER_SESSION_VAULT_FETCH_AFTER_DEPOSIT] viewer=$accountAddress remaining=$onChainRemaining",
                                            )
                                            if (onChainRemaining != null) {
                                                runOnUiThread {
                                                    viewModel.setViewerSessionVaultBalance(
                                                        onChainRemaining,
                                                        resetVoucherUsage = true,
                                                    )
                                                }
                                            } else {
                                                Log.e(
                                                    TAG,
                                                    "[VIEWER_SESSION_VAULT_FETCH_AFTER_DEPOSIT_NULL] viewer=$accountAddress",
                                                )
                                            }
                                            startViewerOnChainRefresh(
                                                viewerAddress = accountAddress,
                                                hostAddress = payToAddress,
                                            )
                                        }.onFailure {
                                            Log.e(TAG, "❌ Session Vault openSession+deposit failed", it)
                                        }
                                    return approval
                                }
                            },
                        clientConfig = ClientConfig(autoPaySegments = false),
                    ).also {
                        it.rtcClient.onPaymentRequested = { request ->
                            connectedHostAddress = request.payTo.takeIf { it.isNotBlank() }
                            connectedPaymentSessionId = request.id.takeIf { it.isNotBlank() } ?: connectedPaymentSessionId
                            Log.e(
                                TAG,
                                "[VIEWER_PAYMENT_REQUEST_RECEIVED] session=${request.id} payTo=${request.payTo} amount=${request.amount} asset=${request.asset}",
                            )
                        }
                        it.rtcClient.onPaymentReceipt = { receipt ->
                            lifecycleScope.launch {
                                connectedHostAddress = receipt.payTo.takeIf { it.isNotBlank() } ?: connectedHostAddress
                                connectedPaymentSessionId = receipt.sessionId.takeIf { it.isNotBlank() } ?: connectedPaymentSessionId
                                val debit = receipt.amount.toLongOrNull() ?: 0L
                                val viewerAddress = receipt.payFrom.ifBlank { accountAddress }
                                val blocksConsumed = (receipt.segmentIndex + 1).coerceAtLeast(0)
                                viewerBlocksConsumedByRefresh = maxOf(viewerBlocksConsumedByRefresh, blocksConsumed)
                                val voucherUsed =
                                    MppPayments.computeVoucherMicroUsdcUsage(blocksConsumed)
                                        .coerceAtMost(MppPayments.voucherSettleWindowMicroUsdc())

                                Log.d(
                                    TAG,
                                    "💸 MPP receipt accepted: session=${receipt.sessionId} segment=${receipt.segmentIndex} amount=${receipt.amount} asset=${receipt.asset} txId=${receipt.txId}",
                                )

                                if (viewerAddress.isNotBlank()) {
                                    val voucherSignature =
                                        runCatching {
                                            val claimAppId =
                                                com.michaeltchuang.walletsdk.core.railmpp.utils
                                                    .RailMppConstants
                                                    .MPP_SESSION_VAULT_APP_ID
                                            val claimMessageHash =
                                                MppPayments.buildClaimMessageHashHex(
                                                    appId = claimAppId,
                                                    totalAmountClaimedMicroUsdc = voucherUsed,
                                                )
                                            Log.e(
                                                TAG,
                                                "[VIEWER_CLAIM_MSG_HASH] source=receipt session=${receipt.sessionId} appId=$claimAppId totalAmountClaimedMicroUsdc=$voucherUsed hash=$claimMessageHash",
                                            )
                                            val message =
                                                MppPayments.buildClaimMessage(
                                                    appId = claimAppId,
                                                    totalAmountClaimedMicroUsdc = voucherUsed,
                                                )
                                            viewModel.signFido2Challenge(message, accountAddress)
                                        }.getOrNull()

                                    if (voucherSignature != null && voucherSignature.isNotEmpty()) {
                                        runViewerClaimSignatureSelfTest(
                                            sessionId = receipt.sessionId,
                                            appId =
                                                com.michaeltchuang.walletsdk.core.railmpp.utils
                                                    .RailMppConstants
                                                    .MPP_SESSION_VAULT_APP_ID,
                                            totalAmountClaimedMicroUsdc = voucherUsed,
                                            viewerAddress = viewerAddress,
                                            signature = voucherSignature,
                                        )
                                        val voucherJson =
                                            MppPayments.createVoucherJson(
                                                sessionId = receipt.sessionId,
                                                viewerAddress = viewerAddress,
                                                creatorAddress = receipt.payTo,
                                                blocksConsumed = blocksConsumed,
                                                totalAmountUsed = voucherUsed,
                                                remainingMicroUsdc = viewModel.viewerSessionVaultMicroUsdc.value,
                                            )
                                        val signatureBase64 = MppPayments.serializeVoucherSignature(voucherSignature)
                                        val voucherPayload =
                                            JSONObject(voucherJson).apply {
                                                put("signature", signatureBase64)
                                            }
                                        viewModel.signalService.value?.send(voucherPayload.toString())
                                        Log.e(
                                            TAG,
                                            "[VIEWER_VOUCHER_SENT] session=${receipt.sessionId} blocks=$blocksConsumed used=$voucherUsed sigBytes=${voucherSignature.size}",
                                        )
                                    }
                                }
                                if (debit > 0L) {
                                    runOnUiThread { viewModel.applyViewerSegmentDebit(debit) }
                                }

                                val hostAddress = receipt.payTo.ifBlank { connectedHostAddress.orEmpty() }
                                val onChainRemaining =
                                    if (hostAddress.isBlank()) {
                                        null
                                    } else {
                                        MppPayments.getRemainingBalanceFromSessionVault(
                                            viewerAddress = viewerAddress,
                                            hostAddress = hostAddress,
                                            appId = com.michaeltchuang.walletsdk.core.railmpp.utils.RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                                        )
                                    }
                                Log.e(
                                    TAG,
                                    "[VIEWER_SESSION_VAULT_FETCH_ON_RECEIPT] viewer=$viewerAddress segment=${receipt.segmentIndex} remaining=$onChainRemaining",
                                )
                                if (onChainRemaining != null) {
                                    runOnUiThread {
                                        viewModel.setViewerSessionVaultBalance(onChainRemaining)
                                    }
                                } else {
                                    Log.e(
                                        TAG,
                                        "[VIEWER_SESSION_VAULT_FETCH_ON_RECEIPT_NULL] viewer=$viewerAddress segment=${receipt.segmentIndex}",
                                    )
                                }
                            }
                        }
                        it.rtcClient.onStreamGated = { reason ->
                            Log.e(TAG, "⛔ Viewer stream gated by creator: $reason")
                            runOnUiThread {
                                val terms =
                                    ConsentTerms(
                                        gatingMode = com.michaeltchuang.walletsdk.core.railmpp.core.GatingMode.PARTIAL_TIME,
                                        amount = "100000",
                                        asset = USDC_TESTNET_ID.toString(),
                                        network = mppNetwork,
                                        segmentDuration = 3,
                                    )
                                lifecycleScope.launch {
                                    runCatching {
                                        viewModel.requestMppConsentFromUi(terms)
                                    }
                                }
                            }
                        }
                        it.start()
                    }
            } catch (_: CancellationException) {
                // Activity is tearing down.
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize MPP payment viewer", e)
            }
        }
    }

    private fun startViewerOnChainRefresh(
        viewerAddress: String,
        hostAddress: String,
    ) {
        viewerSessionStartRemainingMicroUsdc =
            viewModel.viewerSessionVaultMicroUsdc.value
                .takeIf { it > 0L }
        if (viewerAddress.isBlank()) {
            Log.e(TAG, "[VIEWER_SESSION_VAULT_REFRESH_SKIP] reason=blank_viewer")
            return
        }
        Log.e(
            TAG,
            "[VIEWER_SESSION_VAULT_REFRESH_START] viewer=$viewerAddress host=$hostAddress baselineSeed=${viewerSessionStartRemainingMicroUsdc}",
        )
        stopViewerOnChainRefresh()
        viewerLastObservedBlockNumber = null
        viewerLastVoucherSentBlocks = 0
        viewerOnChainRefreshJob =
            lifecycleScope.launch {
                while (isActive) {
                    runCatching {
                        viewModel.currentBlockNumber.value
                    }.onSuccess { currentBlock ->
                        if (currentBlock != null) {
                            val lastBlock = viewerLastObservedBlockNumber
                            if (lastBlock != null && currentBlock > lastBlock) {
                                val delta = (currentBlock - lastBlock).toInt().coerceAtLeast(1)
                                viewerBlocksConsumedByRefresh += delta
                            }
                            viewerLastObservedBlockNumber = currentBlock
                        }
                    }

                    runCatching {
                        MppPayments.getRemainingBalanceFromSessionVault(
                            viewerAddress = viewerAddress,
                            hostAddress = hostAddress,
                            appId = com.michaeltchuang.walletsdk.core.railmpp.utils.RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                        )
                    }.onSuccess { remaining ->
                        Log.e(
                            TAG,
                            "[VIEWER_SESSION_VAULT_REFRESH_TICK] viewer=$viewerAddress host=$hostAddress blocks=$viewerBlocksConsumedByRefresh remaining=$remaining",
                        )
                        if (remaining != null) {
                            runOnUiThread {
                                viewModel.setViewerSessionVaultBalance(remaining)
                            }

                            if (viewerSessionStartRemainingMicroUsdc == null) {
                                viewerSessionStartRemainingMicroUsdc = remaining
                                Log.e(
                                    TAG,
                                    "[VIEWER_SESSION_VAULT_BASELINE_SET] session=${connectedPaymentSessionId.orEmpty()} host=$hostAddress baselineRemaining=$remaining",
                                )
                            }

                            val blocksConsumed = viewerBlocksConsumedByRefresh.coerceAtLeast(0)
                            if (
                                blocksConsumed > 0 &&
                                blocksConsumed > viewerLastVoucherSentBlocks
                            ) {
                                val sessionCap = (viewerSessionStartRemainingMicroUsdc ?: remaining).coerceAtLeast(0L)
                                val voucherUsed =
                                    MppPayments.computeVoucherMicroUsdcUsage(blocksConsumed)
                                        .coerceAtMost(sessionCap)
                                        .coerceAtMost(MppPayments.voucherSettleWindowMicroUsdc())
                                val voucherSignature =
                                    runCatching {
                                        val claimAppId =
                                            com.michaeltchuang.walletsdk.core.railmpp.utils
                                                .RailMppConstants
                                                .MPP_SESSION_VAULT_APP_ID
                                        val claimMessageHash =
                                            MppPayments.buildClaimMessageHashHex(
                                                appId = claimAppId,
                                                totalAmountClaimedMicroUsdc = voucherUsed,
                                            )
                                        Log.e(
                                            TAG,
                                            "[VIEWER_CLAIM_MSG_HASH] source=refresh session=${connectedPaymentSessionId.orEmpty()} appId=$claimAppId totalAmountClaimedMicroUsdc=$voucherUsed hash=$claimMessageHash",
                                        )
                                        val message =
                                            MppPayments.buildClaimMessage(
                                                appId = claimAppId,
                                                totalAmountClaimedMicroUsdc = voucherUsed,
                                            )
                                        viewModel.signFido2Challenge(message, viewerAddress)
                                    }.getOrNull()

                                if (voucherSignature != null && voucherSignature.isNotEmpty()) {
                                    val sessionId = connectedPaymentSessionId?.takeIf { it.isNotBlank() }
                                        ?: run {
                                            Log.e(
                                                TAG,
                                                "[VIEWER_VOUCHER_SKIP] reason=session_id_missing blocks=$blocksConsumed host=$hostAddress",
                                            )
                                            null
                                        }
                                    if (sessionId == null) {
                                        Log.e(
                                            TAG,
                                            "[VIEWER_VOUCHER_SKIP] reason=session_id_missing_no_delay blocks=$blocksConsumed host=$hostAddress",
                                        )
                                    } else {
                                        runViewerClaimSignatureSelfTest(
                                            sessionId = sessionId,
                                            appId =
                                                com.michaeltchuang.walletsdk.core.railmpp.utils
                                                    .RailMppConstants
                                                    .MPP_SESSION_VAULT_APP_ID,
                                            totalAmountClaimedMicroUsdc = voucherUsed,
                                            viewerAddress = viewerAddress,
                                            signature = voucherSignature,
                                        )
                                        val voucherJson =
                                            MppPayments.createVoucherJson(
                                                sessionId = sessionId,
                                                viewerAddress = viewerAddress,
                                                creatorAddress = hostAddress,
                                                blocksConsumed = blocksConsumed,
                                                totalAmountUsed = voucherUsed,
                                                remainingMicroUsdc = remaining,
                                            )
                                        val signatureBase64 = MppPayments.serializeVoucherSignature(voucherSignature)
                                        val voucherPayload =
                                            JSONObject(voucherJson).apply {
                                                put("signature", signatureBase64)
                                            }
                                        viewModel.signalService.value?.send(voucherPayload.toString())
                                        viewerLastVoucherSentBlocks = blocksConsumed
                                        Log.e(
                                            TAG,
                                            "[VIEWER_VOUCHER_SENT] source=refresh session=$sessionId blocks=$blocksConsumed used=$voucherUsed capMicroUsdc=${viewerSessionStartRemainingMicroUsdc ?: remaining} sigBytes=${voucherSignature.size}",
                                        )
                                    }
                                } else {
                                    Log.e(
                                        TAG,
                                        "[VIEWER_VOUCHER_SKIP] source=refresh reason=signature_missing blocks=$blocksConsumed",
                                    )
                                }
                            }
                        }
                    }.onFailure {
                        Log.e(
                            TAG,
                            "[VIEWER_SESSION_VAULT_REFRESH_ERR] viewer=$viewerAddress",
                            it,
                        )
                    }
                    kotlinx.coroutines.delay(1000L)
                }
            }
    }

    private fun stopViewerOnChainRefresh() {
        viewerOnChainRefreshJob?.cancel()
        viewerOnChainRefreshJob = null
        connectedPaymentSessionId = null
        viewerBlocksConsumedByRefresh = 0
        viewerLastObservedBlockNumber = null
        viewerLastVoucherSentBlocks = 0
        viewerSessionStartRemainingMicroUsdc = null
    }

    private suspend fun awaitPaymentDataChannel(
        service: com.michaeltchuang.walletsdk.core.liquidAuth.auth.connect.SignalService,
    ): DataChannel? {
        service.getDataChannel(PAYMENT_CHANNEL_LABEL)?.let { return it }
        repeat(20) {
            service.getDataChannel(PAYMENT_CHANNEL_LABEL)?.let { return it }
            kotlinx.coroutines.delay(100)
        }
        service.createDataChannel(PAYMENT_CHANNEL_LABEL)?.let { return it }

        return suspendCancellableCoroutine { continuation ->
            val handler = Handler(mainLooper)
            val poll =
                object : Runnable {
                    override fun run() {
                        val channel = service.getDataChannel(PAYMENT_CHANNEL_LABEL)
                        if (!continuation.isActive) return
                        if (channel != null) {
                            continuation.resume(channel)
                        } else {
                            handler.postDelayed(this, 100)
                        }
                    }
                }
            handler.postDelayed(poll, 100)
            continuation.invokeOnCancellation { handler.removeCallbacks(poll) }
        }
    }

    private fun buildMppWalletSigner(address: String): MppWalletSigner {
        return object : MppWalletSigner {
            override val address: String = address

            override suspend fun signTransaction(txn: com.algorand.algosdk.transaction.Transaction): ByteArray {
                val localAccount = viewModel.resolveLocalAccount(address) ?: error("No local account for $address")
                return when (localAccount) {
                    is com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount.Algo25 -> {
                        val secretKey = viewModel.resolveAlgo25SecretKey(address) ?: error("Missing Algo25 key for $address")
                        val txnBytes = Encoder.encodeToMsgPack(txn)
                        val signature =
                            signAlgo25ArbitraryData(txn.bytesToSign(), secretKey)
                                ?: error("Algo25 arbitrary signing failed")
                        Sdk.attachSignature(signature, txnBytes)
                    }
                    is com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount.HdKey -> {
                        val seed = viewModel.resolveSeed(localAccount.seedId) ?: error("Missing HD seed for $address")
                        com.michaeltchuang.walletsdk.core.algosdk.signHdKeyTransaction(
                            transactionByteArray = Encoder.encodeToMsgPack(txn),
                            seed,
                            localAccount.account,
                            localAccount.change,
                            localAccount.keyIndex,
                        ) ?: error("HD signing failed")
                    }
                    is com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount.Falcon24 -> {
                        val secretKey =
                            viewModel.resolveFalcon24SecretKey(address)
                                ?: error("Missing Falcon24 key for $address")
                        com.michaeltchuang.walletsdk.core.algosdk.signFalcon24Transaction(
                            transactionByteArray = Encoder.encodeToMsgPack(txn),
                            localAccount.publicKey,
                            secretKey,
                        ) ?: error("Falcon24 signing failed")
                    }
                    else -> error("Unsupported account for Algorand MPP signing: ${localAccount::class.simpleName}")
                }
            }

            override suspend fun createSolanaSignedTransaction(
                recipientAddress: String,
                amount: String,
                network: String,
                mint: String?,
            ): ByteArray {
                val localAccount = viewModel.resolveLocalAccount(address) ?: error("No local account for $address")
                if (localAccount !is com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount.SeedVault) {
                    error("Unsupported account for Solana MPP signing: ${localAccount::class.simpleName}")
                }

                val recipient = org.sol4k.PublicKey(recipientAddress)
                val sender = org.sol4k.PublicKey(address)
                val connection =
                    org.sol4k.Connection(
                        when {
                            network.contains("mainnet", ignoreCase = true) -> "https://api.mainnet-beta.solana.com"
                            network.contains("testnet", ignoreCase = true) -> "https://api.testnet.solana.com"
                            else -> "https://api.devnet.solana.com"
                        },
                    )
                val blockhash = connection.getLatestBlockhash()

                val instructions: List<org.sol4k.instruction.Instruction> =
                    if (mint.isNullOrBlank() || mint.equals("SOL", ignoreCase = true)) {
                        listOf(org.sol4k.instruction.TransferInstruction(sender, recipient, amount.toLong()))
                    } else {
                        val mintPk = org.sol4k.PublicKey(mint)
                        val (senderAta, _) = org.sol4k.PublicKey.findProgramDerivedAddress(sender, mintPk)
                        val (recipientAta, _) = org.sol4k.PublicKey.findProgramDerivedAddress(recipient, mintPk)

                        val ataIx =
                            if (connection.getAccountInfo(recipientAta) == null) {
                                object : org.sol4k.instruction.Instruction {
                                    override val data: ByteArray = ByteArray(0)
                                    override val keys: List<org.sol4k.AccountMeta> =
                                        listOf(
                                            org.sol4k.AccountMeta(sender, writable = true, signer = true),
                                            org.sol4k.AccountMeta(recipientAta, writable = true, signer = false),
                                            org.sol4k.AccountMeta(recipient, writable = false, signer = false),
                                            org.sol4k.AccountMeta(mintPk, writable = false, signer = false),
                                            org.sol4k.AccountMeta(
                                                org.sol4k.PublicKey("11111111111111111111111111111111"),
                                                writable = false,
                                                signer = false,
                                            ),
                                            org.sol4k.AccountMeta(
                                                org.sol4k.PublicKey("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"),
                                                writable = false,
                                                signer = false,
                                            ),
                                            org.sol4k.AccountMeta(
                                                org.sol4k.PublicKey("SysvarRent111111111111111111111111111111111"),
                                                writable = false,
                                                signer = false,
                                            ),
                                        )
                                    override val programId: org.sol4k.PublicKey =
                                        org.sol4k.PublicKey(
                                            "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL",
                                        )
                                }
                            } else {
                                null
                            }

                        val transferCheckedIx =
                            object : org.sol4k.instruction.Instruction {
                                override val data: ByteArray
                                    get() {
                                        val amountBytes =
                                            java.nio.ByteBuffer
                                                .allocate(
                                                    8,
                                                ).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                                .putLong(amount.toLong())
                                                .array()
                                        return byteArrayOf(12.toByte()) + amountBytes + byteArrayOf(6.toByte())
                                    }

                                override val keys: List<org.sol4k.AccountMeta> =
                                    listOf(
                                        org.sol4k.AccountMeta(senderAta, writable = true, signer = false),
                                        org.sol4k.AccountMeta(mintPk, writable = false, signer = false),
                                        org.sol4k.AccountMeta(recipientAta, writable = true, signer = false),
                                        org.sol4k.AccountMeta(sender, writable = false, signer = true),
                                    )

                                override val programId: org.sol4k.PublicKey =
                                    org.sol4k.PublicKey(
                                        "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",
                                    )
                            }

                        buildList {
                            ataIx?.let { add(it) }
                            add(transferCheckedIx)
                        }
                    }

                val tx = org.sol4k.Transaction(blockhash, instructions, sender)

                val serializedWithSigCount = tx.serialize()
                val message =
                    if (serializedWithSigCount.isNotEmpty() && serializedWithSigCount[0] == 0.toByte()) {
                        serializedWithSigCount.copyOfRange(1, serializedWithSigCount.size)
                    } else {
                        serializedWithSigCount
                    }

                val signature =
                    signChallengeWithSeedVault(message, address)
                        ?: error("Seed Vault Solana signing failed")
                if (signature.size < 64) error("Seed Vault signature must be 64 bytes")

                val signedTx = ByteArray(1 + 64 + message.size)
                signedTx[0] = 1
                signature.copyInto(signedTx, destinationOffset = 1, startIndex = 0, endIndex = 64)
                message.copyInto(signedTx, destinationOffset = 65)
                return signedTx
            }
        }
    }

    private suspend fun resolveMppClientNetwork(address: String): String {
        val localAccount = viewModel.resolveLocalAccount(address)
        return if (localAccount is com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount.SeedVault) {
            MppNetworks.SOLANA_DEVNET
        } else {
            MppNetworks.ALGORAND_TESTNET
        }
    }

    /**
     * Authentication using a PublicKeyCredential (Step 1 of 2)
     *
     * Prepares authentication by fetching assertion options from the FIDO2 Server
     * Delegates to use case for better separation of concerns
     */
    private fun authenticate(
        msg: AuthMessage,
        credential: String,
    ) {
        viewModel.authenticate(
            authMessage = msg,
            credentialId = credential,
            setSession = { sessionId -> sessionId?.let { setSession(it) } },
            onCredentialNotFound = {
                lifecycleScope.launch {
                    viewModel.deleteCredentialByAccountAddress(algoAddress!!)
                    showToast(
                        "Credential not found on server. Re-registering...",
                        Toast.LENGTH_LONG,
                    )
                    register(msg)
                }
            },
        )
    }

    /**
     * Authentication using a PublicKeyCredential (Step 2 of 2)
     *
     * Handles the Activity result from the FIDO2 API Client's assertion request.
     * Delegates to use case for clean separation of concerns.
     */
    private fun handleAuthenticatorAssertionResultCallback(result: HandleAssertionResultUseCase.Result) {
        viewModel.handleAssertionResultFromLauncher(result)
    }

    /** Update Render for demonstration purposes only */
    private fun setSession(s: String?) {
        if (s === null) {
            viewModel.setSession("Logged Out")
            viewModel.setMessage(null)
        } else {
            viewModel.setSession(s)
        }
    }

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        try {
            val params =
                PictureInPictureParams
                    .Builder()
                    .setAspectRatio(Rational(9, 16))
                    .setSourceRectHint(createPipSourceRectHint())
                    .build()
            shouldEnterPipMode = true
            enterPictureInPictureMode(params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enter PiP mode", e)
            shouldEnterPipMode = false
        }
    }

    private fun createPipSourceRectHint(): Rect {
        val root = window?.decorView
        val width = root?.width?.takeIf { it > 0 } ?: 1080
        val height = root?.height?.takeIf { it > 0 } ?: 1920

        // Keep PiP anchor much higher from bottom so it clears bottom-nav actions.
        val bottomPaddingPx = (220 * resources.displayMetrics.density).toInt()
        val pipWidth = (width * 0.33f).toInt().coerceAtLeast(320)
        val pipHeight = (pipWidth * 16f / 9f).toInt()

        val left = (width - pipWidth - (16 * resources.displayMetrics.density).toInt()).coerceAtLeast(0)
        val top = (height - pipHeight - bottomPaddingPx).coerceAtLeast(0)

        return Rect(left, top, left + pipWidth, top + pipHeight)
    }

    private fun resolveSeedVaultSigner(address: String): SeedVaultSigner? {
        val seedsCursor =
            try {
                Wallet.getAuthorizedSeeds(this, WalletContractV1.AUTHORIZED_SEEDS_ALL_COLUMNS)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to query Seed Vault authorized seeds", e)
                return null
            }

        seedsCursor?.use { cursor ->
            while (cursor.moveToNext()) {
                val authToken = cursor.getLong(0)
                val accountsCursor =
                    try {
                        Wallet.getAccounts(
                            this,
                            authToken,
                            WalletContractV1.ACCOUNTS_ALL_COLUMNS,
                            WalletContractV1.ACCOUNTS_PUBLIC_KEY_ENCODED,
                            address,
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to query Seed Vault accounts for authToken=$authToken", e)
                        continue
                    }

                accountsCursor?.use { accountCursor ->
                    if (accountCursor.moveToFirst()) {
                        val derivationPath = Uri.parse(accountCursor.getString(1))
                        return SeedVaultSigner(authToken = authToken, derivationPath = derivationPath)
                    }
                }
            }
        }

        return null
    }

    private suspend fun signChallengeWithSeedVault(
        challenge: ByteArray,
        address: String,
    ): ByteArray? =
        suspendCancellableCoroutine<ByteArray?> { continuation ->
            if (pendingSeedVaultSignContinuation != null) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }

            val signer = resolveSeedVaultSigner(address)
            if (signer == null) {
                Log.e(TAG, "No Seed Vault signer metadata found for address=$address")
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }

            pendingSeedVaultSignContinuation = continuation
            continuation.invokeOnCancellation {
                if (pendingSeedVaultSignContinuation === continuation) {
                    pendingSeedVaultSignContinuation = null
                }
            }

            try {
                val intent =
                    Wallet.signMessage(
                        this,
                        signer.authToken,
                        signer.derivationPath,
                        challenge,
                    )
                seedVaultSignMessageLauncher.launch(intent)
            } catch (e: Exception) {
                if (pendingSeedVaultSignContinuation === continuation) {
                    pendingSeedVaultSignContinuation = null
                }
                Log.e(TAG, "Failed launching Seed Vault signMessage intent", e)
                continuation.resume(null)
            }
        }

    private suspend fun signChallengeForAccount(
        challenge: ByteArray,
        accountAddress: String,
    ): ByteArray? {
        val isSeedVaultAccount = viewModel.isSeedVaultAccount(accountAddress)
        return if (isSeedVaultAccount) {
            signChallengeWithSeedVault(challenge, accountAddress)
        } else {
            viewModel.signFido2Challenge(challenge, accountAddress)
        }
    }

    private fun verifyClaimSignatureLocally(
        message: ByteArray,
        signature: ByteArray,
        viewerAddress: String,
    ): Boolean =
        runCatching {
            val publicKey = Address(viewerAddress).getBytes()
            if (publicKey.size != 32 || signature.size != 64) return false

            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
            verifier.update(message, 0, message.size)
            verifier.verifySignature(signature)
        }.getOrDefault(false)

    private fun runViewerClaimSignatureSelfTest(
        sessionId: String,
        appId: Long,
        totalAmountClaimedMicroUsdc: Long,
        viewerAddress: String,
        signature: ByteArray,
    ) {
        val message =
            MppPayments.buildClaimMessage(
                appId = appId,
                totalAmountClaimedMicroUsdc = totalAmountClaimedMicroUsdc,
            )
        val hash =
            MppPayments.buildClaimMessageHashHex(
                appId = appId,
                totalAmountClaimedMicroUsdc = totalAmountClaimedMicroUsdc,
            )
        val positive = verifyClaimSignatureLocally(message, signature, viewerAddress)
        val negative = verifyClaimSignatureLocally(message + byteArrayOf(0x00), signature, viewerAddress)
        Log.e(
            TAG,
            "[VIEWER_CLAIM_SIG_SELF_TEST] session=$sessionId viewer=$viewerAddress appId=$appId totalAmountClaimedMicroUsdc=$totalAmountClaimedMicroUsdc hash=$hash positiveVerify=$positive negativeVerify=$negative sigLen=${signature.size}",
        )
    }

    /**
     * Sign challenge and launch registration intent
     */
    private suspend fun signChallengeAndLaunchRegistration(
        pubKeyCredentialCreationOptions: PublicKeyCredentialCreationOptions,
        algoAddress: String,
    ) {
        // Sign the challenge with the selected account
        val signature =
            signChallengeForAccount(
                challenge = pubKeyCredentialCreationOptions.challenge,
                accountAddress = algoAddress,
            )

        if (signature == null) {
            Log.e(TAG, "Failed to sign FIDO2 challenge")
            showToast(
                "Failed to sign challenge: unable to retrieve account credentials",
                Toast.LENGTH_LONG,
            )
            return
        }

        // Store signature for later use
        viewModel.currentChallenge = signature
        Log.d(TAG, "✅ Challenge signed successfully")

        // Launch FIDO2 registration intent
        launchRegistrationIntent(pubKeyCredentialCreationOptions)
    }

    /**
     * Launch FIDO2 registration intent
     */
    private suspend fun launchRegistrationIntent(pubKeyCredentialCreationOptions: PublicKeyCredentialCreationOptions) {
        try {
            Log.d(TAG, "========================================")
            Log.d(TAG, "🔐 LAUNCHING PASSKEY REGISTRATION")
            Log.d(TAG, "========================================")

            val pendingIntent =
                fido2Client!!.getRegisterPendingIntent(pubKeyCredentialCreationOptions).await()
            val intentSenderRequest = IntentSenderRequest.Builder(pendingIntent).build()
            attestationIntentLauncher.launch(intentSenderRequest)

            Log.d(TAG, "✅ Intent launched - waiting for user interaction...")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch FIDO2 intent", e)
            showToast("Failed to launch biometric: ${e.message}", Toast.LENGTH_LONG)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (shouldEnterPipMode) {
            enterPipMode()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (!isInPictureInPictureMode) {
            shouldEnterPipMode = false
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        super.onBackPressed()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        shouldEnterPipMode = false
        stopViewerOnChainRefresh()
        liquidStreamViewer?.terminate()
        liquidStreamViewer = null
        viewModel.clearVideoFrame() // Clear video when activity destroyed
        viewModel.unbindSignalService(this)
    }
}
