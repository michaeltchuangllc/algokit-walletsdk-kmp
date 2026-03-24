package com.michaeltchuang.walletsdk.ui.liquidAuth

import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.StrictMode
import android.util.Log
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
import com.google.android.gms.fido.fido2.Fido2ApiClient
import com.google.android.gms.fido.fido2.api.common.PublicKeyCredential
import com.google.android.gms.fido.fido2.api.common.PublicKeyCredentialCreationOptions
import com.michaeltchuang.walletsdk.core.liquidAuth.auth.connect.AuthMessage
import com.michaeltchuang.walletsdk.core.liquidAuth.auth.connect.SignalClient
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.liquidAuth.AnswerViewModel.Companion.SERVICE_NOTIFICATION_ID
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases.HandleAssertionResultUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.screens.AnswerScreen
import com.michaeltchuang.walletsdk.ui.liquidAuth.screens.ConfirmTransferScreen
import foundation.algorand.provider.Message
import foundation.algorand.provider.avm.models.ResponseMessage
import foundation.algorand.provider.avm.models.SignTransactionsParams
import foundation.algorand.provider.avm.models.SignTransactionsResult
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.json.JSONObject
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.webrtc.DataChannel
import com.solanamobile.seedvault.Wallet
import com.solanamobile.seedvault.WalletContractV1
import java.security.Security
import kotlinx.coroutines.CancellableContinuation
import kotlin.coroutines.resume
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class AnswerActivity : AppCompatActivity() {
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

    private lateinit var params: SignTransactionsParams
    private lateinit var message: Message

    // Authenticate/Assertion Intent Channel
    private lateinit var assertionIntentLauncher: ActivityResultLauncher<IntentSenderRequest>

    private lateinit var attestationIntentLauncher: ActivityResultLauncher<IntentSenderRequest>
    private lateinit var seedVaultSignMessageLauncher: ActivityResultLauncher<Intent>
    private var pendingSeedVaultSignContinuation: CancellableContinuation<ByteArray?>? = null

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
                    AnswerScreen(viewModel = viewModel)
                    if (showDialogState.value) {
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
                                    processTransactionSigning(params, message)
                                    viewModel.showConfirmationDialog.value = false
                                }
                            },
                            onClose = {
                                viewModel.showConfirmationDialog.value = false
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

        // Only auto-connect if we don't have an intent (deep link or QR scan)
        val hasIntent = intent?.data != null
        val hasValidAuthMessage =
            AuthMessageStorage.authMessage.origin.isNotEmpty() &&
                AuthMessageStorage.authMessage.requestId.isNotEmpty()

        if (!hasIntent && hasValidAuthMessage) {
            Log.d(TAG, "No intent detected, auto-connecting with stored AuthMessage")
            Handler().postDelayed({
                connect(AuthMessageStorage.authMessage)
            }, 2000)
        } else {
            if (!hasValidAuthMessage) {
                Log.d(TAG, "No valid AuthMessage stored, skipping auto-connect")
            } else {
                Log.d(TAG, "Intent detected, skipping auto-connect (will use scanned QR data)")
            }
        }
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
        // Use ViewModel's handleMessages method
        viewModel.handleMessages(
            msgStr = msgStr,
            onSignTransaction = { params, message ->
                this.params = params
                this.message = message
                // Show confirmation dialog - the signing logic is inside the OK button handler
                viewModel.showConfirmationDialog.value = true
            },
            onVideoFrame = { frameData ->
                // Update video frame directly in ViewModel
                viewModel.setVideoFrame(frameData)
            },
        )
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
                        Log.d(TAG, "Sending Credential")
                        viewModel.signalService.value?.send(
                            viewModel
                                .getCredentialMessage(
                                    algoAddress!!,
                                    credential,
                                ).toString(),
                        )
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

    override fun onDestroy() {
        super.onDestroy()
        viewModel.clearVideoFrame() // Clear video when activity destroyed
        viewModel.unbindSignalService(this)
    }
}
