package com.michaeltchuang.walletsdk.ui.liquidAuth

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.StrictMode
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.fido.Fido
import com.google.android.gms.fido.fido2.Fido2ApiClient
import com.google.android.gms.fido.fido2.api.common.AuthenticatorErrorResponse
import com.google.android.gms.fido.fido2.api.common.ErrorCode
import com.google.android.gms.fido.fido2.api.common.PublicKeyCredential
import com.michaeltchuang.walletsdk.core.liquidAuth.auth.Cookie
import com.michaeltchuang.walletsdk.core.liquidAuth.auth.connect.AuthMessage
import com.michaeltchuang.walletsdk.core.liquidAuth.auth.connect.SignalClient
import com.michaeltchuang.walletsdk.core.liquidAuth.auth.connect.SignalService
import com.michaeltchuang.walletsdk.core.liquidAuth.auth.fido2.AssertionApi
import com.michaeltchuang.walletsdk.core.liquidAuth.auth.fido2.toPublicKeyCredentialCreationOptions
import com.michaeltchuang.walletsdk.core.liquidAuth.auth.fido2.toPublicKeyCredentialRequestOptions
import foundation.algorand.auth.fido2.AttestationApi
import foundation.algorand.crypto.EncoderType
import foundation.algorand.provider.Message
import foundation.algorand.provider.avm.models.ResponseMessage
import foundation.algorand.provider.avm.models.SignTransactionsParams
import foundation.algorand.provider.avm.models.SignTransactionsResult
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import okhttp3.OkHttpClient
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.json.JSONArray
import org.json.JSONObject
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.webrtc.DataChannel
import org.webrtc.PeerConnection
import ru.gildor.coroutines.okhttp.await
import java.security.Security
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class AnswerActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "AnswerActivity"
        private const val SHARED_PREFERENCE_SEED_FILE = "ACCOUNT_SEEDS"
        private const val TURN_USERNAME: String = "fc7708976bf5d60be20c5a1d"
        private const val TURN_CREDENTIAL: String = "sVpEREQGGhXOw4gX"
        private const val NODELY_TURN_USERNAME = "liquid-auth"
        private const val NODELY_TURN_CREDENTIAL = "sqmcP4MiTKMT4TGEDSk9jgHY"
        const val EXTRA_ALGO_ADDRESS = "EXTRA_ALGO_ADDRESS"
    }

    fun createIceServer(
        uri: String,
        username: String,
        password: String,
    ): PeerConnection.IceServer =
        PeerConnection.IceServer
            .builder(uri)
            .setUsername(username)
            .setPassword(password)
            .createIceServer()

    // Liquid Auth Service
    private val iceServers =
        listOf(
            PeerConnection.IceServer
                .builder("stun:stun.l.google.com:19302")
                .createIceServer(),
            PeerConnection.IceServer
                .builder("stun:stun1.l.google.com:19302")
                .createIceServer(),
            PeerConnection.IceServer
                .builder("stun:stun2.l.google.com:19302")
                .createIceServer(),
            createIceServer(
                "turn:global.turn.nodely.network:80?transport=tcp",
                NODELY_TURN_USERNAME,
                NODELY_TURN_CREDENTIAL,
            ),
            createIceServer(
                "turns:global.turn.nodely.network:443?transport=tcp",
                NODELY_TURN_USERNAME,
                NODELY_TURN_CREDENTIAL,
            ),
            createIceServer(
                "turn:eu.turn.nodely.io:80?transport=tcp",
                NODELY_TURN_USERNAME,
                NODELY_TURN_CREDENTIAL,
            ),
            createIceServer(
                "turns:eu.turn.nodely.io:443?transport=tcp",
                NODELY_TURN_USERNAME,
                NODELY_TURN_CREDENTIAL,
            ),
            createIceServer(
                "turn:us.turn.nodely.io:80?transport=tcp",
                NODELY_TURN_USERNAME,
                NODELY_TURN_CREDENTIAL,
            ),
            createIceServer(
                "turns:us.turn.nodely.io:443?transport=tcp",
                NODELY_TURN_USERNAME,
                NODELY_TURN_CREDENTIAL,
            ),
            createIceServer(
                "turn:global.relay.metered.ca:80",
                TURN_USERNAME,
                TURN_CREDENTIAL,
            ),
            createIceServer(
                "turn:global.relay.metered.ca:80?transport=tcp",
                TURN_USERNAME,
                TURN_CREDENTIAL,
            ),
            createIceServer(
                "turn:global.relay.metered.ca:443",
                TURN_USERNAME,
                TURN_CREDENTIAL,
            ),
            createIceServer(
                "turns:global.relay.metered.ca:443?transport=tcp",
                TURN_USERNAME,
                TURN_CREDENTIAL,
            ),
        )

    private var mBounded = false
    private var signalService: SignalService? = null
    private var mConnection: ServiceConnection? = null

    private val viewModel: AnswerViewModel by viewModel()
    private val notifications: NotificationViewModel by viewModels() // Handle Notifications

    // Third Party APIs
    private var httpClient =
        OkHttpClient
            .Builder()
            .cookieJar(Cookies())
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    private lateinit var promptInfo: BiometricPrompt.PromptInfo

    // FIDO/Auth interfaces
    private var fido2Client: Fido2ApiClient? = null
    private var signalClient: SignalClient? = null
    private val attestationApi = AttestationApi(httpClient)
    private var attestationApiResponse: String? = null
    private val assertionApi = AssertionApi(httpClient)
    private var signature: ByteArray? = null

    private var algoAddress: String? = null
    private var mnemonic: String? = null

    // Transaction signing confirmation dialog state
    private var pendingTransactionParams: SignTransactionsParams? = null
    private var pendingTransactionMessage: Message? = null

    // Register/Attestation Intent Launcher
    private val attestationIntentLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult(),
            ::handleAuthenticatorAttestationResult,
        )

    // Authenticate/Assertion Intent Channel
    private val assertionIntentLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult(),
            ::handleAuthenticatorAssertionResult,
        )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Set up Compose screen
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AnswerScreen(
                        viewModel = viewModel,
                    )
                }
            }
        }

        // Initialize ViewModel with API instances that use the Activity's httpClient
        // This is critical for maintaining cookies/session across requests
        viewModel.initializeApis(attestationApi, assertionApi)

        algoAddress = intent.getStringExtra(EXTRA_ALGO_ADDRESS)
        lifecycleScope.launch {
            algoAddress?.let {
                mnemonic = viewModel.getMnemonic(it)

                // Set account address in ViewModel for AVMProvider
                viewModel.setAccountAddress(it)
                
                // 🧪 TESTING FLAG: Set to true to clear stored credentials on app start
                // This forces fresh registration every time (useful for testing)
                val clearCredentialsOnStart = false
                if (clearCredentialsOnStart) {
                    Log.w(TAG, "🧪 TEST MODE: Clearing all stored credentials for $it")
                    viewModel.deleteCredentialByAlgoAddress(it)
                }
            }
        }
        // Set Security
        val policy =
            StrictMode.ThreadPolicy
                .Builder()
                .permitAll()
                .build()
        StrictMode.setThreadPolicy(policy)
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 0)

        // Create FIDO Client, TODO: refactor to Credential Manager
        fido2Client = Fido2ApiClient(this@AnswerActivity)

        // Log app signature for debugging FIDO2 verification issues
        try {
            val packageInfo =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageManager.getPackageInfo(
                        packageName,
                        android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(
                        packageName,
                        android.content.pm.PackageManager.GET_SIGNATURES,
                    )
                }

            val signatures =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageInfo.signingInfo?.apkContentsSigners
                } else {
                    @Suppress("DEPRECATION")
                    packageInfo.signatures
                }

            signatures?.forEach { signature ->
                val md = java.security.MessageDigest.getInstance("SHA-256")
                md.update(signature.toByteArray())
                val hash = md.digest()
                val hexString = hash.joinToString(":") { byte -> "%02X".format(byte) }
                Log.d(TAG, "========================================")
                Log.d(TAG, "📱 APP SIGNATURE (SHA-256)")
                Log.d(TAG, "Package: $packageName")
                Log.d(TAG, "Fingerprint: $hexString")
                Log.d(TAG, "========================================")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get app signature", e)
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        initWebRTCService { hydrateIntents() }

        // Only auto-connect if we don't have an intent (deep link or QR scan)
        val hasIntent = intent?.data != null
        val hasValidAuthMessage = AuthMessageStorage.authMessage.origin.isNotEmpty() && 
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
            signalService?.dataChannel is DataChannel &&
                signalService?.dataChannel?.state() === DataChannel.State.OPEN
        val isIntent = intent != null
        val isDeepLink = intent?.data != null && intent.data is Uri
        val isDataChannelMessage = intent?.getStringExtra("msg") != null
        if (isDeepLink) {
            signalService!!.updateDeepLinkFlag(true)
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
                            signalService!!.updateLastKnownReferer(appId)
                        }
                    }
                }
            }
            // Find the Referrer in the Activity
            this@AnswerActivity.referrer?.let {
                Log.d(TAG, "Referrer: $it")
                signalService!!.updateLastKnownReferer(it.toString())
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
            signalService?.start(
                msg.origin,
                httpClient,
                notifications.createNotificationBuilder(this@AnswerActivity),
                NotificationViewModel.SERVICE_NOTIFICATION_ID,
                AnswerActivity::class.java,
            )

            // Launch the authentication process
            lifecycleScope.launch {
                val savedCredential = viewModel.getCredentialIdByAlgoAddress(algoAddress!!)
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
        if (mBounded) {
            return
        }
        notifications.createChannels(
            getSystemService(NOTIFICATION_SERVICE) as NotificationManager,
        )
        // Handle the Service Connection
        mConnection =
            object : ServiceConnection {
                override fun onServiceDisconnected(name: ComponentName) {
                    mBounded = false
                    signalService = null
                }

                override fun onServiceConnected(
                    name: ComponentName,
                    service: IBinder,
                ) {
                    mBounded = true
                    val mLocalBinder = service as SignalService.LocalBinder
                    signalService = mLocalBinder.getServerInstance()
                    onServiceConnection()
                }
            }
        val startIntent = Intent(this, SignalService::class.java)
        startService(startIntent)
        bindService(startIntent, mConnection as ServiceConnection, BIND_AUTO_CREATE)
    }

    /** Transaction Biometric Prompt */
    private suspend fun biometrics(message: SignTransactionsParams): BiometricPrompt.AuthenticationResult? =
        suspendCoroutine { continuation ->
            var biometricPrompt =
                BiometricPrompt(
                    this@AnswerActivity,
                    ContextCompat.getMainExecutor(this@AnswerActivity),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            super.onAuthenticationSucceeded(result)
                            continuation.resume(result)
                        }

                        override fun onAuthenticationFailed() {
                            super.onAuthenticationFailed()
                            continuation.resume(null)
                        }
                    },
                )
            promptInfo =
                BiometricPrompt.PromptInfo
                    .Builder()
                    .setTitle("Transaction(s) ${message.txns.size}")
                    .setSubtitle("Provider: ${message.providerId}")
                    .setNegativeButtonText("Cancel")
                    .build()
            biometricPrompt.authenticate(promptInfo)
        }

    /**
     * Show Transaction Signing Confirmation Dialog
     *
     * Displays a confirmation dialog before signing transactions.
     * User must click OK to proceed with biometric authentication and signing.
     */
    private fun showTransactionConfirmationDialog(
        params: SignTransactionsParams,
        message: Message,
    ) {
        // Store the pending transaction parameters
        pendingTransactionParams = params
        pendingTransactionMessage = message

        // Build and show the dialog
        val builder =
            androidx.appcompat.app.AlertDialog
                .Builder(this@AnswerActivity)

        // Create dialog title
        val title =
            if (params.txns.size == 1) {
                "Sign Transaction"
            } else {
                "Sign ${params.txns.size} Transactions"
            }

        builder.setTitle(title)

        // Create dialog message
        val messageText = StringBuilder()
        messageText.append("Do you want to sign the following transaction(s)?\n\n")
        messageText.append("Provider: ${params.providerId}\n")
        messageText.append("Number of transactions: ${params.txns.size}\n")

        builder.setMessage(messageText.toString())

        builder.setPositiveButton("OK") { dialog, which ->
            // User confirmed, proceed with biometric authentication
            dialog.dismiss()
            pendingTransactionParams?.let { transactionParams ->
                pendingTransactionMessage?.let { transactionMessage ->
                    lifecycleScope.launch {
                        // Show biometric prompt
                        val result = biometrics(transactionParams)
                        if (result != null) {
                            // Biometric succeeded, proceed with signing
                            Log.d(TAG, "========================================")
                            Log.d(TAG, "✅ BIOMETRIC AUTHENTICATION SUCCESSFUL")
                            Log.d(TAG, "Processing transaction signing...")
                            Log.d(TAG, "========================================")
                            
                            val resultMessage =
                                viewModel.handleMessage(transactionMessage) as ResponseMessage
                            when (resultMessage.result) {
                                is SignTransactionsResult -> {
                                    val signResult = resultMessage.result as SignTransactionsResult
                                    Log.d(TAG, "========================================")
                                    Log.d(TAG, "📤 SENDING SIGNED TRANSACTIONS")
                                    Log.d(TAG, "Number of signed txns: ${signResult.stxns.size}")
                                    Log.d(TAG, "Provider ID: ${signResult.providerId}")
                                    Log.d(TAG, "Response ID: ${resultMessage.id}")
                                    Log.d(TAG, "Response Reference: ${resultMessage.reference}")
                                    Log.d(TAG, "Request ID: ${resultMessage.requestId}")
                                    
                                    // Log the response as JSON for debugging
                                    val responseJson = resultMessage.toJson()
                                    Log.d(TAG, "Response JSON: $responseJson")
                                    Log.d(TAG, "========================================")
                                    
                                    signalService!!.send(
                                        Base64.UrlSafe.encode(
                                            resultMessage.toByteArray(EncoderType.CBOR),
                                        ),
                                    )
                                    
                                    Log.d(TAG, "✅ Signed transactions sent successfully!")
                                }

                                else -> {
                                    Log.e(TAG, "Unknown result type")
                                }
                            }
                        } else {
                            // Biometric failed or was cancelled
                            runOnUiThread {
                                Toast
                                    .makeText(
                                        this@AnswerActivity,
                                        "Transaction signing cancelled",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                            }
                        }
                    }
                }
            }
            // Clear pending parameters
            pendingTransactionParams = null
            pendingTransactionMessage = null
        }

        builder.setNegativeButton("Cancel") { dialog, which ->
            // User cancelled
            dialog.dismiss()
            // Clear pending parameters
            pendingTransactionParams = null
            pendingTransactionMessage = null
            // Show cancellation message
            Toast
                .makeText(
                    this@AnswerActivity,
                    "Transaction signing cancelled",
                    Toast.LENGTH_SHORT,
                ).show()
        }

        builder.setCancelable(false) // Prevent dialog from being cancelled by clicking outside
        builder.create().show()
    }

    /**
     * Handle Messages
     *
     * Callback for datachannel messages
     */
    @OptIn(ExperimentalEncodingApi::class)
    private fun handleMessages(msgStr: String) {
        // Use ViewModel's handleMessages method
        viewModel.handleMessages(msgStr) { params, message ->
            // Show confirmation dialog - the signing logic is inside the OK button handler
            showTransactionConfirmationDialog(params, message)
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

        // Check if message is empty/invalid
        if (msg.origin.isEmpty() || msg.requestId.isEmpty()) {
            Log.w(TAG, "⚠️ connect() called with empty AuthMessage, skipping")
            return
        }

        // Decode Barcode Message
        viewModel.setMessage(msg)
        viewModel.clearError()
        signalService!!.updateDeepLinkFlag(false)
        signalService?.start(
            msg.origin,
            httpClient,
            notifications.createNotificationBuilder(this@AnswerActivity),
            NotificationViewModel.SERVICE_NOTIFICATION_ID,
            AnswerActivity::class.java,
        )
        // Connect to Service
        lifecycleScope.launch {
              val savedCredential = viewModel.getCredentialIdByAlgoAddress(algoAddress!!)
            signalClient = SignalClient(msg.origin, this@AnswerActivity, httpClient)
            if (savedCredential === null) {
                register(msg)
            } else {
                authenticate(msg, savedCredential)
            }
        }
    }

    /**
     * Registration of a new Credential (Step 1 of 2)
     *
     * Receives PublicKeyCredentialCreationOptions from the FIDO2 Server and launches the
     * authenticator Intent using the handleAuthenticatorAttestationResult Handler
     */
    private suspend fun register(
        msg: AuthMessage,
        options: JSONObject = JSONObject(),
    ) {
        try {
            val account = algoAddress
            Log.d(TAG, "========================================")
            Log.d(TAG, "🔐 REGISTER() CALLED")
            Log.d(TAG, "Account: $algoAddress")
            Log.d(TAG, "Origin: ${msg.origin}")
            Log.d(TAG, "RequestID: ${msg.requestId}")
            Log.d(TAG, "========================================")

            // Extract the domain from the origin URL for RP ID
            val rpId =
                try {
                    val parsedUri = Uri.parse(msg.origin)
                    val host = parsedUri.host
                    if (host.isNullOrEmpty()) {
                        Log.e(TAG, "Failed to extract host from origin: ${msg.origin}")
                        throw IllegalArgumentException("Invalid origin URL: ${msg.origin}")
                    }
                    Log.d(TAG, "Extracted host from origin: $host")
                    host
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse origin URL: ${msg.origin}", e)
                    runOnUiThread {
                        Toast
                            .makeText(
                                this@AnswerActivity,
                                "Invalid origin URL: ${msg.origin}",
                                Toast.LENGTH_LONG,
                            ).show()
                    }
                    return
                }
            Log.d(TAG, "Using RP ID: $rpId for origin: ${msg.origin}")

            // Check for tunneling services (ngrok, localhost, etc.)
            val isTunnel =
                rpId.contains("ngrok") ||
                    rpId.contains("localhost") ||
                    rpId.contains("127.0.0.1") ||
                    rpId.contains(".local")
            if (isTunnel) {
                Log.w(TAG, "⚠️ Detected tunneling/local service: $rpId")
                Log.w(
                    TAG,
                    "FIDO2 may have issues with tunneling services. Ensure your ngrok/tunnel is properly configured.",
                )
            }

            // Create Options for FIDO2 Server
            options.put("username", algoAddress.toString())
            options.put("displayName", "Liquid Auth User")

            // Set authenticator selection with explicit requirements
            val authenticatorSelection = JSONObject()
            authenticatorSelection.put(
                "authenticatorAttachment",
                "platform",
            ) // Use device biometrics
            authenticatorSelection.put(
                "userVerification",
                "required",
            ) // Require biometric verification
            authenticatorSelection.put("requireResidentKey", false) // Don't require resident key
            options.put("authenticatorSelection", authenticatorSelection)

            Log.d(TAG, "Authenticator selection: $authenticatorSelection")

            // Explicitly set the RP to match the origin
            val rp = JSONObject()
            rp.put("id", rpId)
            rp.put("name", "Liquid Auth")
            options.put("rp", rp)

            val extensions = JSONObject()
            extensions.put("liquid", true)
            options.put("extensions", extensions)

            Log.d(TAG, "Request options: $options")

            // FIDO2 Server API Response for PublicKeyCredentialCreationOptions
            Log.d(TAG, "========================================")
            Log.d(TAG, "📡 SENDING HTTP REQUEST")
            Log.d(TAG, "URL: ${msg.origin}/attestation/request")
            Log.d(TAG, "User-Agent: ${viewModel.userAgent}")
            Log.d(TAG, "========================================")

            val response =
                try {
                    viewModel.fetchAttestationOptions(msg.origin, viewModel.userAgent, options)
                } catch (e: Exception) {
                    Log.e(TAG, "Network error when contacting FIDO2 server", e)
                    viewModel.setError("Network error: ${e.message}")
                    runOnUiThread {
                        Toast
                            .makeText(
                                this@AnswerActivity,
                                "Network error: ${e.message}\nCannot reach ${msg.origin}",
                                Toast.LENGTH_LONG,
                            ).show()
                    }
                    return
                }

            if (!response.isSuccessful) {
                Log.e(
                    TAG,
                    "Failed to get attestation options: ${response.code} ${response.message}",
                )
                val errorBody =
                    try {
                        response.body?.string()
                    } catch (e: Exception) {
                        "Could not read error body"
                    }
                Log.e(TAG, "Error body: $errorBody")
                viewModel.setError("Server error ${response.code}: ${response.message}")
                runOnUiThread {
                    Toast
                        .makeText(
                            this@AnswerActivity,
                            "Server error ${response.code}: ${response.message}",
                            Toast.LENGTH_LONG,
                        ).show()
                }
                return
            } else {
                Log.d(TAG, "HTTP request successful")
                attestationApiResponse = response.peekBody(Long.MAX_VALUE).string()
                Log.d(TAG, "Mithilesh: FIDO2 Options: $attestationApiResponse")
            }

            val session = Cookie.fromResponse(response)
            session?.let { setSession(Cookie.getID(it)) }

            // Convert ResponseBody to FIDO2 PublicKeyCredentialCreationOptions
            // Override the RP ID to match our origin since the server may return incorrect value

            val pubKeyCredentialCreationOptions =
                response.body!!.toPublicKeyCredentialCreationOptions(
                    overrideRpId = rpId,
                )

            Log.d(TAG, "PublicKeyCredentialCreationOptions created successfully")
            Log.d(TAG, "RP ID: ${pubKeyCredentialCreationOptions.rp?.id}")
            Log.d(TAG, "RP Name: ${pubKeyCredentialCreationOptions.rp?.name}")
            Log.d(TAG, "User: ${pubKeyCredentialCreationOptions.user?.name}")
            Log.d(TAG, "Challenge length: ${pubKeyCredentialCreationOptions.challenge?.size}")

            // Sign the challenge with the algorand account, this is used in the liquid FIDO2 extension
            val signatureResult =
                viewModel.signFido2Challenge(
                    pubKeyCredentialCreationOptions.challenge,
                    algoAddress!!,
                )

            /*val signatureResult =
                KeyPairs.rawSignBytes(
                    pubKeyCredentialCreationOptions.challenge,
                    KeyPairs.getKeyPair(viewModel.getMnemonic(algoAddress!!)!!).private,
                )*/

            if (signatureResult == null) {
                Log.e(TAG, "Failed to sign FIDO2 challenge - signature is null")
                runOnUiThread {
                    Toast
                        .makeText(
                            this@AnswerActivity,
                            "Failed to sign challenge: unable to retrieve account credentials",
                            Toast.LENGTH_LONG,
                        ).show()
                }
                return
            }

            signature = signatureResult
            Log.d(TAG, "Signature created, launching FIDO2 intent")

            // Kick off FIDO2 Client Intent
            try {
                Log.d(TAG, "Calling getRegisterPendingIntent...")
                val pendingIntent =
                    fido2Client!!.getRegisterPendingIntent(pubKeyCredentialCreationOptions).await()
                Log.d(TAG, "PendingIntent received: $pendingIntent")

                val intentSenderRequest = IntentSenderRequest.Builder(pendingIntent).build()
                Log.d(TAG, "========================================")
                Log.d(TAG, "🔐 LAUNCHING PASSKEY REGISTRATION")
                Log.d(TAG, "You should see a biometric/passkey popup now...")
                Log.d(TAG, "========================================")
                attestationIntentLauncher.launch(intentSenderRequest)
                Log.d(TAG, "Intent launched - waiting for user interaction...")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch FIDO2 intent", e)
                Log.e(TAG, "Exception type: ${e.javaClass.name}")
                Log.e(TAG, "Exception message: ${e.message}")
                e.printStackTrace()
                runOnUiThread {
                    Toast
                        .makeText(
                            this@AnswerActivity,
                            "Failed to launch biometric: ${e.message}",
                            Toast.LENGTH_LONG,
                        ).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during registration", e)
            runOnUiThread {
                Toast
                    .makeText(
                        this@AnswerActivity,
                        "Registration error: ${e.message}",
                        Toast.LENGTH_LONG,
                    ).show()
            }
        }
    }

    /**
     * Registration of a New Credential (Step 2 of 2)
     *
     * Handles the ActivityResult from a FIDO2 Intent and submits the Authenticator's
     * PublicKeyCredential to the FIDO2 Server
     */
    @OptIn(ExperimentalEncodingApi::class)
    private fun handleAuthenticatorAttestationResult(activityResult: ActivityResult) {
        try {
            Log.d(TAG, "========================================")
            Log.d(TAG, "📱 ATTESTATION RESULT RECEIVED")
            Log.d(TAG, "Result code: ${activityResult.resultCode}")
            Log.d(TAG, "RESULT_OK = $RESULT_OK")
            Log.d(TAG, "Data: ${activityResult.data}")
            Log.d(TAG, "========================================")

            val bytes = activityResult.data?.getByteArrayExtra(Fido.FIDO2_KEY_CREDENTIAL_EXTRA)
            Log.d(TAG, "Bytes extracted: ${if (bytes == null) "NULL" else "size=${bytes.size}"}")

            // Check what extras are in the intent
            activityResult.data?.extras?.let { extras ->
                Log.d(TAG, "Intent extras keys: ${extras.keySet().joinToString()}")
            } ?: Log.d(TAG, "No extras in intent")

            when {
                activityResult.resultCode != RESULT_OK -> {
                    Log.e(
                        TAG,
                        "Attestation cancelled or failed. Result code: ${activityResult.resultCode}",
                    )

                    // Try to get error details from the intent
                    activityResult.data?.let { data ->
                        val errorBytes = data.getByteArrayExtra(Fido.FIDO2_KEY_ERROR_EXTRA)
                        if (errorBytes != null) {
                            try {
                                val errorResponse =
                                    PublicKeyCredential.deserializeFromBytes(errorBytes)
                                if (errorResponse.response is AuthenticatorErrorResponse) {
                                    val error = errorResponse.response as AuthenticatorErrorResponse
                                    Log.e(TAG, "FIDO2 Error Code: ${error.errorCode}")
                                    Log.e(TAG, "FIDO2 Error Message: ${error.errorMessage}")
                                    Toast
                                        .makeText(
                                            this@AnswerActivity,
                                            "FIDO2 Error: ${error.errorMessage}",
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    return
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to parse error response", e)
                            }
                        }
                    }

                    Toast
                        .makeText(this@AnswerActivity, "Attestation canceled", Toast.LENGTH_LONG)
                        .show()
                }

                bytes == null -> {
                    Log.e(TAG, "Credential bytes are null")
                    Toast
                        .makeText(
                            this@AnswerActivity,
                            "Error: No credential data",
                            Toast.LENGTH_LONG,
                        ).show()
                }

                else -> {
                    Log.d(TAG, "✅ Credential bytes received, size: ${bytes.size}")

                    // Handle PublicKeyCredential Response from Authenticator
                    val credential = PublicKeyCredential.deserializeFromBytes(bytes)
                    Log.d(TAG, "Credential ID: ${credential.id}")
                    Log.d(TAG, "Credential Type: ${credential.type}")

                    val response = credential.response
                    Log.d(TAG, "Response type: ${response.javaClass.simpleName}")

                    if (response is AuthenticatorErrorResponse) {
                        Log.e(TAG, "❌ FIDO2 AUTHENTICATOR ERROR")
                        Log.e(TAG, "Error Code: ${response.errorCode}")
                        Log.e(TAG, "Error Code Name: ${response.errorCode.name}")
                        Log.e(TAG, "Error Message: ${response.errorMessage}")

                        if (response.errorCode === ErrorCode.UNKNOWN_ERR) {
                            Toast
                                .makeText(
                                    this@AnswerActivity,
                                    "Something Went Wrong: ${response.errorMessage}",
                                    Toast.LENGTH_LONG,
                                ).show()
                        } else {
                            Toast
                                .makeText(
                                    this@AnswerActivity,
                                    "FIDO2 Error: ${response.errorMessage}",
                                    Toast.LENGTH_LONG,
                                ).show()
                        }
                        return
                    } else {
                        if (signature === null) {
                            Toast
                                .makeText(
                                    this@AnswerActivity,
                                    "Signature is null",
                                    Toast.LENGTH_LONG,
                                ).show()
                            return
                        }
                        val msg = viewModel.message.value!!
                        
                        lifecycleScope.launch {
                            // Create the Liquid Extension JSON
                            val liquidExtJSON = JSONObject()
                            val accountType = viewModel.getAccountTypeForFido2(algoAddress!!)
                            liquidExtJSON.put("type", accountType)
                            liquidExtJSON.put("requestId", msg.requestId)
                            liquidExtJSON.put("address", algoAddress.toString())
                            liquidExtJSON.put("publicKey", Base64.encode(viewModel.getAccountPublicKey(algoAddress.toString())))
                            liquidExtJSON.put("signature", Base64.encode(signature!!))
                            liquidExtJSON.put("device", Build.MODEL)
                            Log.d(TAG, "========================================")
                            Log.d(TAG, "📤 POSTING CREDENTIAL TO SERVER")
                            Log.d(TAG, "URL: ${msg.origin}/attestation/response")
                            Log.d(TAG, "Account Type: $accountType")
                            Log.d(TAG, "Algo Address: $algoAddress")
                            Log.d(TAG, "Credential ID: ${credential.id}")
                            Log.d(TAG, "Liquid Extension: $liquidExtJSON")
                            Log.d(TAG, "========================================")

                            // POST Authenticator Results to FIDO2 API
                            val attestationResponse =
                                viewModel.submitAttestationResult(
                                    msg.origin,
                                    viewModel.userAgent,
                                    credential,
                                    liquidExtJSON,
                                )

                            Log.d(TAG, "========================================")
                            Log.d(TAG, "📡 ATTESTATION RESPONSE RECEIVED")
                            Log.d(TAG, "HTTP Status: ${attestationResponse.code} ${attestationResponse.message}")
                            Log.d(TAG, "Credential ID: ${credential.id}")
                            Log.d(TAG, "Account: $algoAddress")
                            
                            // Log the full response body for debugging
                            val responseBody = attestationResponse.peekBody(Long.MAX_VALUE).string()
                            Log.d(TAG, "Response body: $responseBody")
                            Log.d(TAG, "========================================")
                            
                            if (!attestationResponse.isSuccessful) {
                                Log.e(TAG, "❌ REGISTRATION FAILED!")
                                Log.e(TAG, "Server rejected the credential")
                                Log.e(TAG, "Status: ${attestationResponse.code}")
                                Log.e(TAG, "Response: $responseBody")
                                
                                runOnUiThread {
                                    Toast
                                        .makeText(
                                            this@AnswerActivity,
                                            "❌ Registration failed: ${attestationResponse.code} - Check server logs",
                                            Toast.LENGTH_LONG,
                                        ).show()
                                }
                                return@launch
                            }
                            
                            Log.d(TAG, "✅ FIDO2 REGISTRATION SUCCESSFUL!")
                            Log.d(TAG, "========================================")

                            runOnUiThread {
                                Toast
                                    .makeText(
                                        this@AnswerActivity,
                                        "✅ Registration successful! Credential saved.",
                                        Toast.LENGTH_LONG,
                                    ).show()
                            }

                            viewModel.saveCredential(
                                this@AnswerActivity,
                                algoAddress!!,
                                credential,
                                attestationApiResponse!!,
                            )
                            Log.d(TAG, "Credential Saved to local storage")

                            // 🧪 TESTING FLAG: Set to true to auto-test authentication instead of WebRTC
                            val testAuthenticationInstead = true

                            if (testAuthenticationInstead) {
                                // Test mode: Skip WebRTC and test authentication
                                kotlinx.coroutines.delay(2000) // Wait 2 seconds
                                Log.d(TAG, "========================================")
                                Log.d(TAG, "🧪 TEST MODE: TESTING AUTHENTICATION")
                                Log.d(
                                    TAG,
                                    "Now attempting to authenticate with saved credential...",
                                )
                                Log.d(TAG, "Credential ID: ${credential.id}")
                                Log.d(TAG, "========================================")
                                credential.id?.let { credId ->
                                    authenticate(msg, credId)
                                } ?: Log.e(TAG, "Cannot test authentication: credential ID is null")
                                return@launch // Skip WebRTC flow
                            }

                            // Normal flow: Continue with WebRTC setup
                            if (mBounded) {
                                Log.d(TAG, "Service Bonded")
                                signalService?.peer(msg.requestId, "answer", iceServers)
                                runOnUiThread {
                                    if (signalService!!.isDeepLink) this@AnswerActivity.onBackPressed()
                                }
                                signalService?.handleMessages(
                                    this@AnswerActivity,
                                    { peerMsg ->
                                        Log.d(TAG, "handleMessages($peerMsg)")
                                        handleMessages(peerMsg)
                                    },
                                    {
                                        Log.d(TAG, "onStateChange($it)")
                                        if (it === "OPEN") {
                                            Log.d(TAG, "Sending Credential")
                                            signalService?.send(
                                                viewModel
                                                    .getCredentialMessage(
                                                        algoAddress!!,
                                                        credential,
                                                    ).toString(),
                                            )
                                        }
                                    },
                                    notifications.createNotificationBuilder(this@AnswerActivity),
                                    NotificationViewModel.SERVICE_NOTIFICATION_ID,
                                    AnswerActivity::class.java,
                                )
                            } else {
                                Toast
                                    .makeText(
                                        this@AnswerActivity,
                                        "Couldn't find service",
                                        Toast.LENGTH_LONG,
                                    ).show()
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception in handleAuthenticatorAttestationResult", e)
            Log.e(TAG, "Exception type: ${e.javaClass.name}")
            Log.e(TAG, "Exception message: ${e.message}")
            e.printStackTrace()
            runOnUiThread {
                Toast
                    .makeText(
                        this@AnswerActivity,
                        "Error processing attestation: ${e.message}",
                        Toast.LENGTH_LONG,
                    ).show()
            }
        }
    }

    /**
     * Authentication using a PublicKeyCredential (Step 1 of 2)
     *
     * Receives PublicKeyCredentialRequestOptions from the FIDO2 Server and launches the
     * authenticator Intent using the handleAuthenticatorAssertionResult Handler
     */
    private suspend fun authenticate(
        msg: AuthMessage,
        credential: String,
    ) {
        Log.d(TAG, "========================================")
        Log.d(TAG, "🔓 STARTING AUTHENTICATION")
        Log.d(TAG, "Origin: ${msg.origin}")
        Log.d(TAG, "Credential: $credential")
        Log.d(TAG, "========================================")

        val response = viewModel.fetchAssertionOptions(msg.origin, viewModel.userAgent, credential)

        Log.d(TAG, "Got assertion options from server")
        Log.d(TAG, "HTTP Status: ${response.code} ${response.message}")
        Log.d(TAG, "Response headers: ${response.headers}")

        val session = Cookie.fromResponse(response)
        session?.let { setSession(Cookie.getID(it)) }

        // Log the response body for debugging
        val responseBodyString = response.body?.string()
        Log.d(TAG, "Assertion options response body: $responseBodyString")
        Log.d(TAG, "Response body length: ${responseBodyString?.length ?: 0} characters")

        // Check if response is successful
        if (!response.isSuccessful) {
            Log.e(TAG, "Server returned error response: ${response.code} ${response.message}")
            
            // If credential not found (401), delete local credential and re-register
            if (response.code == 401 && responseBodyString?.contains("not_found") == true) {
                Log.w(TAG, "⚠️ Credential not found on server - will re-register")
                Log.w(TAG, "Deleting local credential: $credential")
                
                // Delete the invalid credential from local storage
                viewModel.deleteCredentialByAlgoAddress(algoAddress!!)
                
                runOnUiThread {
                    Toast
                        .makeText(
                            this@AnswerActivity,
                            "Credential not found on server. Re-registering...",
                            Toast.LENGTH_LONG,
                        ).show()
                }
                
                // Re-register with the server
                Log.d(TAG, "Starting registration process...")
                register(msg)
                return
            }
            
            runOnUiThread {
                Toast
                    .makeText(
                        this@AnswerActivity,
                        "Server error: ${response.code} ${response.message}",
                        Toast.LENGTH_LONG,
                    ).show()
            }
            return
        }

        val publicKeyCredentialRequestOptions =
            try {
                // Recreate the response body since we consumed it above
                val recreatedBody =
                    responseBodyString?.let {
                        okhttp3.ResponseBody.create(
                            response.body?.contentType(),
                            it,
                        )
                    }

                if (recreatedBody == null) {
                    throw IllegalArgumentException("Response body is null")
                }

                recreatedBody.toPublicKeyCredentialRequestOptions()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse PublicKeyCredentialRequestOptions", e)
                runOnUiThread {
                    Toast
                        .makeText(
                            this@AnswerActivity,
                            "Failed to parse authentication options: ${e.message}",
                            Toast.LENGTH_LONG,
                        ).show()
                }
                return
            }

        Log.d(TAG, "========================================")
        Log.d(TAG, "🔐 LAUNCHING PASSKEY AUTHENTICATION")
        Log.d(TAG, "You should see a biometric/passkey popup now...")
        Log.d(TAG, "========================================")

        val pendingIntent =
            fido2Client!!.getSignPendingIntent(publicKeyCredentialRequestOptions).await()
        assertionIntentLauncher.launch(IntentSenderRequest.Builder(pendingIntent).build())

        Log.d(TAG, "Authentication intent launched - waiting for user...")
    }

    /**
     * Authentication using a PublicKeyCredential (Step 2 of 2)
     *
     * Handles the ActivityResult from a FIDO2 Intent and submits the Authenticator's
     * PublicKeyCredential to the FIDO2 Server
     */
    private fun handleAuthenticatorAssertionResult(activityResult: ActivityResult) {
        val bytes = activityResult.data?.getByteArrayExtra(Fido.FIDO2_KEY_CREDENTIAL_EXTRA)
        when {
            activityResult.resultCode != RESULT_OK ->
                Toast.makeText(this@AnswerActivity, "Canceled", Toast.LENGTH_LONG).show()

            bytes == null ->
                Toast
                    .makeText(this@AnswerActivity, "Authenticate Error", Toast.LENGTH_LONG)
                    .show()

            else -> {
                // Handle PublicKeyCredential Response from Authenticator
                val credential = PublicKeyCredential.deserializeFromBytes(bytes)
                val pubKeyCredentialResponse = credential.response
                if (pubKeyCredentialResponse is AuthenticatorErrorResponse) {
                    Toast
                        .makeText(
                            this@AnswerActivity,
                            pubKeyCredentialResponse.errorMessage,
                            Toast.LENGTH_LONG,
                        ).show()
                } else {
                    Log.d(TAG, "✅ Authentication credential received")
                    Log.d(TAG, "Credential ID: ${credential.id}")

                    lifecycleScope.launch {
                        val liquidExtJSON = JSONObject()
                        val accountType = viewModel.getAccountTypeForFido2(algoAddress!!)
                        liquidExtJSON.put("type", accountType)
                        liquidExtJSON.put("requestId", viewModel.message.value!!.requestId)

                        Log.d(TAG, "📤 Posting authentication assertion to server...")

                        // POST Authenticator Results to FIDO2 API
                        val response =
                            viewModel.submitAssertionResult(
                                viewModel.message.value!!.origin,
                                viewModel.userAgent,
                                credential,
                                liquidExtJSON,
                            )

                        Log.d(TAG, "========================================")
                        Log.d(TAG, "✅ AUTHENTICATION SUCCESSFUL!")
                        Log.d(TAG, "Server response: ${response.code}")
                        Log.d(TAG, "Credential was recognized and validated!")
                        Log.d(TAG, "========================================")

                        runOnUiThread {
                            Toast
                                .makeText(
                                    this@AnswerActivity,
                                    "🔓 Authentication Successful!\nUsing saved credential",
                                    Toast.LENGTH_LONG,
                                ).show()
                        }

                        // Update Render/State
                        val data = response.body!!.string()
                        val json = JSONObject(data)
                        val creds = json.get("credentials") as JSONArray

                        if (creds.length() > 0) {
                            for (i in 0 until creds.length()) {
                                val cred: JSONObject = creds.getJSONObject(i)
                                if (cred.get("credId") == credential.id) {
                                    viewModel.setCount(cred.get("prevCounter") as Int)
                                }
                            }
                        } else {
                            viewModel.setCount(0)
                        }
                        val msg = viewModel.message.value!!
                        if (mBounded) {
                            signalService?.peer(msg.requestId, "answer", iceServers)
                            runOnUiThread {
                                if (signalService!!.isDeepLink) this@AnswerActivity.onBackPressed()
                            }
                            signalService?.handleMessages(
                                this@AnswerActivity,
                                { peerMsg ->
                                    Log.d(TAG, "handleMessages($peerMsg)")
                                    handleMessages(peerMsg)
                                },
                                {
                                    Log.d(TAG, "onStateChange($it)")
                                    if (it === "OPEN") {
                                        Log.d(TAG, "Sending Credential")
                                        signalService?.send(
                                            viewModel
                                                .getCredentialMessage(
                                                    algoAddress!!,
                                                    credential,
                                                ).toString(),
                                        )
                                    }
                                },
                                notifications.createNotificationBuilder(this@AnswerActivity),
                                NotificationViewModel.SERVICE_NOTIFICATION_ID,
                                AnswerActivity::class.java,
                            )
                        } else {
                            Toast
                                .makeText(
                                    this@AnswerActivity,
                                    "Couldn't find service",
                                    Toast.LENGTH_LONG,
                                ).show()
                        }
                    }
                }
            }
        }
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
}

@Composable
fun AnswerScreen(viewModel: AnswerViewModel) {
    // Collect StateFlow values as Compose state
    val session by viewModel.session.collectAsState()
    val message by viewModel.message.collectAsState()
    val accountAddress by viewModel.accountAddress.collectAsState()
    val errorMessage by viewModel.error.collectAsState()

    // Determine connection status
    val isWaiting = message == null && errorMessage == null
    val hasError = errorMessage != null
    val isConnected = message != null && session != "Logged Out" && !hasError
    val isConnecting = message != null && session == "Logged Out" && !hasError

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Header
            Text(
                text = "Liquid Auth",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Connection Status Card
            ConnectionStatusCard(
                isConnected = isConnected,
                isWaiting = isWaiting,
                isConnecting = isConnecting,
                hasError = hasError,
                errorMessage = errorMessage,
                session = session,
                origin = message?.origin,
                requestId = message?.requestId,
                accountAddress = accountAddress,
            )

            // Account Info Card (if account address exists)
            if (accountAddress.isNotEmpty()) {
                AccountInfoCard(accountAddress = accountAddress)
            }
        }
    }
}

@Composable
fun ConnectionStatusCard(
    isConnected: Boolean,
    isWaiting: Boolean,
    isConnecting: Boolean,
    hasError: Boolean,
    errorMessage: String?,
    session: String,
    origin: String?,
    requestId: String?,
    accountAddress: String?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    when {
                        hasError -> MaterialTheme.colorScheme.errorContainer
                        isWaiting -> MaterialTheme.colorScheme.surfaceVariant
                        isConnecting -> MaterialTheme.colorScheme.tertiaryContainer
                        isConnected -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.errorContainer
                    },
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Status Icon and Loader
            if (hasError) {
                // Error state - show error icon and message
                Text(
                    text = "⚠",
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = "Connection Failed",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.9f),
                        textAlign = TextAlign.Center,
                    )
                }
                Text(
                    text = "Session: $session",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f),
                )
            } else if (isWaiting) {
                // Waiting for connection - show loader
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 4.dp,
                )
                Text(
                    text = "Waiting for connection...",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Scan a QR code or use deep link to connect",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                )
            } else if (isConnecting) {
                // Connecting - show loader with connection info
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    color = MaterialTheme.colorScheme.tertiary,
                    strokeWidth = 4.dp,
                )
                Text(
                    text = "Connecting...",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    text = "Establishing secure connection",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                )

                // Show connection details while connecting
                Spacer(modifier = Modifier.height(8.dp))

                if (origin != null) {
                    InfoRow(label = "Origin", value = origin)
                }
                if (requestId != null) {
                    InfoRow(label = "Request ID", value = requestId)
                }
            } else if (isConnected) {
                // Connected - show success icon
                Text(
                    text = "✓",
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "Connected",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )

                // Connection Details
                Spacer(modifier = Modifier.height(8.dp))

                if (origin != null) {
                    InfoRow(label = "Origin", value = origin)
                }
                if (requestId != null) {
                    InfoRow(label = "Request ID", value = requestId)
                }
                InfoRow(label = "Session", value = session)
            } else {
                // Disconnected state (no error, but not connected)
                Text(
                    text = "⚠",
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = "Disconnected",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = "Session: $session",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                )
            }
        }
    }
}

@Composable
fun AccountInfoCard(accountAddress: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Account Information",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )

            InfoRow(label = "Address", value = accountAddress)
        }
    }
}

@Composable
fun InfoRow(
    label: String,
    value: String,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Normal,
        )
    }
}
