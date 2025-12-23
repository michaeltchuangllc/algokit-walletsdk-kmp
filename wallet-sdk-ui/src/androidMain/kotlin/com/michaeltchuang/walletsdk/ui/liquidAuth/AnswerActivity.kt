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
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.algorand.algosdk.account.Account
import com.algorand.algosdk.transaction.Transaction
import com.algorand.algosdk.util.Encoder
import com.fasterxml.uuid.Generators
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
import com.michaeltchuang.walletsdk.core.liquidAuth.provider.AVMProvider
import foundation.algorand.auth.fido2.AttestationApi
import foundation.algorand.crypto.EncoderType
import foundation.algorand.crypto.avm.KeyPairs
import foundation.algorand.provider.Message
import foundation.algorand.provider.avm.models.RequestMessage
import foundation.algorand.provider.avm.models.ResponseMessage
import foundation.algorand.provider.avm.models.SignTransactionsParams
import foundation.algorand.provider.avm.models.SignTransactionsResult
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import okhttp3.OkHttpClient
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.PeerConnection
import ru.gildor.coroutines.okhttp.await
import java.security.Security
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class AnswerActivity : AppCompatActivity() {
    val TURN_USERNAME: String = "fc7708976bf5d60be20c5a1d"
    val TURN_CREDENTIAL: String = "sVpEREQGGhXOw4gX"
    val NODELY_TURN_USERNAME = "liquid-auth"
    val NODELY_TURN_CREDENTIAL = "sqmcP4MiTKMT4TGEDSk9jgHY"

    companion object {
        private const val TAG = "AnswerActivity"
        private const val SHARED_PREFERENCE_SEED_FILE = "ACCOUNT_SEEDS"
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

    private val viewModel: AnswerViewModel by viewModels() // Handle View State
    private val wallet: WalletViewModel by viewModels() // Handle Wallet Operations
    private val notifications: NotificationViewModel by viewModels() // Handle Notifications

    // Third Party APIs
    private var httpClient = OkHttpClient.Builder().cookieJar(Cookies()).build()
    private lateinit var promptInfo: BiometricPrompt.PromptInfo

    // FIDO/Auth interfaces
    private var fido2Client: Fido2ApiClient? = null
    private var signalClient: SignalClient? = null
    private val attestationApi = AttestationApi(httpClient)
    private val assertionApi = AssertionApi(httpClient)
    val APPLICATION_ID = "com.michaeltchuang.walletsdk.demo"
    val VERSION_NAME = "1.0"
    private val userAgent =
        "${APPLICATION_ID}/${VERSION_NAME} " +
            "(Android ${Build.VERSION.RELEASE}; ${Build.MODEL}; ${Build.BRAND})"
    private var signature: ByteArray? = null

    // Datachannel Provider/Handler
    val uuidGenerator = Generators.timeBasedEpochRandomGenerator()

    // Must be unique to this provider
    val providerId = uuidGenerator.generate().toString()
    private val provider = AVMProvider(providerId)

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
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.GET_SIGNATURES)
            }
            
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
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
        
        // Load the Shared Preferences
        hydrateSharedPreferences()
        //  fromUri(liquid://debug.liquidauth.com/?requestId=019b41a1-d33c-7559-9eff-98e7644e3bcd)

        /* // Ensure the device has notifications enabled
         val notificationManager =
                 getSystemService(NOTIFICATION_SERVICE) as NotificationManager
         if (!notificationManager.areNotificationsEnabled()) {
             val notificationsDialogFragment = NotificationsDialogFragment(packageName)
             if (!notificationsDialogFragment.isVisible) {
                 notificationsDialogFragment.show(supportFragmentManager, "NOTIFICATIONS")
             }
         }*/
        // Ensure the device is secure to access FIDO/Passkeys
        /*  val keyguardManager =
                  this@AnswerActivity.getSystemService(KEYGUARD_SERVICE) as KeyguardManager
          if (!keyguardManager.isDeviceSecure) {
              val settingsDialogFragment = SettingsDialogFragment()
              if (!settingsDialogFragment.isVisible) {
                  settingsDialogFragment.show(supportFragmentManager, "CREATED")
              }
          }*/

        /* val passKeysMnemonicFragment = PassKeysMnemonicDialogFragment()
         if (!passKeysMnemonicFragment.isVisible &&
                         derivedSecretRepository.getDerivedParentSecret(this@AnswerActivity) == null
         ) {
             passKeysMnemonicFragment.show(supportFragmentManager, "MNEMONIC_INPUT")
         }*/
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        initWebRTCService { hydrateIntents() }

        // Only auto-connect if we don't have an intent (deep link or QR scan)
        val hasIntent = intent?.data != null
        if (!hasIntent) {
            Log.d(TAG, "No intent detected, auto-connecting with stored AuthMessage")
            Handler().postDelayed({
                connect(AuthMessageStorage.AuthMessage)
            }, 2000)
        } else {
            Log.d(TAG, "Intent detected, skipping auto-connect (will use scanned QR data)")
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
            signalService?.start(
                msg.origin,
                httpClient,
                notifications.createNotificationBuilder(this@AnswerActivity),
                NotificationViewModel.SERVICE_NOTIFICATION_ID,
                AnswerActivity::class.java,
            )

            // Launch the authentication process
            lifecycleScope.launch {
                val savedCredential = null
                // credentialRepository.getCredentialByOrigin(this@AnswerActivity, msg.origin)
                if (savedCredential === null) {
                    register(msg)
                } else {
                    authenticate(msg, "savedCredential")
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

    /**
     * Load seed phrases from SharedPreferences This is not recommended in production applications,
     * it is just for demonstration purposes.
     */
    private fun hydrateSharedPreferences() {
        val sharedPref = getSharedPreferences(SHARED_PREFERENCE_SEED_FILE, MODE_PRIVATE)
        
        // 🔧 CONFIGURATION: To use a specific account, uncomment and paste your 25-word mnemonic here:
        // val importedMnemonic = "word1 word2 word3 word4 word5 word6 word7 word8 word9 word10 word11 word12 word13 word14 word15 word16 word17 word18 word19 word20 word21 word22 word23 word24 word25"
        // val importedAccount = Account(importedMnemonic)
        // Log.d(TAG, "📥 Importing account: ${importedAccount.address}")
        // sharedPref.edit().putString("MAIN_ACCOUNT", importedMnemonic).apply()
        // wallet.setAccount(importedAccount)
        // // Also set rekey and selected to use the imported account
        // wallet.setRekey(importedAccount)
        // wallet.setSelected(importedAccount)
        // return
        
        // Load the stored seed phrases
        sharedPref.getString("MAIN_ACCOUNT", null)?.let { 
            val account = Account(it)
            Log.d(TAG, "✅ Loaded existing account: ${account.address}")
            wallet.setAccount(account)
        }
            ?: run {
                val account = Account()
                Log.d(TAG, "🆕 Created new random account: ${account.address}")
                sharedPref.edit().putString("MAIN_ACCOUNT", account.toMnemonic()).apply()
                wallet.setAccount(account)
            }
        sharedPref.getString("REKEY_ACCOUNT", null)?.let { wallet.setRekey(Account(it)) }
            ?: run {
                val account = Account()
                sharedPref.edit().putString("REKEY_ACCOUNT", account.toMnemonic()).apply()
                wallet.setRekey(account)
            }
        sharedPref.getString("SELECTED_ACCOUNT", null)?.let {
            if (wallet.rekey.value!!.address.toString() == it) {
                wallet.setSelected(wallet.rekey.value!!)
            } else {
                wallet.setSelected(wallet.account.value!!)
            }
        }
            ?: run {
                sharedPref.edit()
                    .putString(
                        "SELECTED_ACCOUNT",
                        wallet.account.value!!.address.toString()
                    ).apply()
                wallet.setSelected(wallet.account.value!!)
            }
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

    /** Decode Unsigned Transaction */
    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeUnsignedTransaction(unsignedTxn: String): Transaction? =
        Encoder.decodeFromMsgPack(Base64.decode(unsignedTxn), Transaction::class.java)

    /**
     * Handle Messages
     *
     * Callback for datachannel messages
     */
    @OptIn(ExperimentalEncodingApi::class)
    private fun handleMessages(msgStr: String) {
        val keyPair = KeyPairs.getKeyPair(wallet.selected.value!!.toMnemonic())
        try {
            val message = Message(Base64.UrlSafe.decode(msgStr), EncoderType.CBOR)
            val request = provider.encoder.decode<RequestMessage>(message.data, message.encoding)
            if (request.reference == "arc0027:sign_transactions:request") {
                lifecycleScope.launch {
                    val params =
                        provider.encoder.decode<SignTransactionsParams>(
                            provider.encoder.encode(request.params, EncoderType.NONE),
                            EncoderType.NONE,
                        )
                    biometrics(params)
                    provider.setKeyPair(keyPair)
                    val resultMessage = provider.handleMessage(message) as ResponseMessage
                    when (resultMessage.result) {
                        is SignTransactionsResult -> {
                            signalService!!.send(
                                Base64.UrlSafe.encode(
                                    resultMessage.toByteArray(EncoderType.CBOR),
                                ),
                            )
                        }

                        else -> {
                            TODO("Not Implemented")
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error: $e")
            runOnUiThread {
                Toast.makeText(this@AnswerActivity, "Error: $msgStr", Toast.LENGTH_SHORT).show()
            }
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
            val savedCredential = null
            /*  credentialRepository.getCredentialByOrigin(
                  this@AnswerActivity,
                  msg.origin
              )*/
            signalClient = SignalClient(msg.origin, this@AnswerActivity, httpClient)
            if (savedCredential === null) {
                register(msg)
            } else {
                authenticate(msg, "savedCredential")
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
            val account = wallet.account.value!!
            val selected = wallet.selected.value!!
            Log.d(TAG, "========================================")
            Log.d(TAG, "🔐 REGISTER() CALLED")
            Log.d(TAG, "Account: ${account.address}")
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
                Log.w(TAG, "FIDO2 may have issues with tunneling services. Ensure your ngrok/tunnel is properly configured.")
            }

            // Create Options for FIDO2 Server
            options.put("username", account.address.toString())
            options.put("displayName", "Liquid Auth User")

            // Set authenticator selection with explicit requirements
            val authenticatorSelection = JSONObject()
            authenticatorSelection.put("authenticatorAttachment", "platform") // Use device biometrics
            authenticatorSelection.put("userVerification", "required") // Require biometric verification
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
            Log.d(TAG, "User-Agent: $userAgent")
            Log.d(TAG, "========================================")

            val response =
                try {
                    attestationApi.postAttestationOptions(msg.origin, userAgent, options).await()
                } catch (e: Exception) {
                    Log.e(TAG, "Network error when contacting FIDO2 server", e)
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
                Log.e(TAG, "Failed to get attestation options: ${response.code} ${response.message}")
                val errorBody =
                    try {
                        response.body?.string()
                    } catch (e: Exception) {
                        "Could not read error body"
                    }
                Log.e(TAG, "Error body: $errorBody")
                runOnUiThread {
                    Toast
                        .makeText(
                            this@AnswerActivity,
                            "Server error ${response.code}: ${response.message}",
                            Toast.LENGTH_LONG,
                        ).show()
                }
                return
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
            signature =
                KeyPairs.rawSignBytes(
                    pubKeyCredentialCreationOptions.challenge,
                    KeyPairs.getKeyPair(selected.toMnemonic()).private,
                )

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
                    Log.e(TAG, "Attestation cancelled or failed. Result code: ${activityResult.resultCode}")

                    // Try to get error details from the intent
                    activityResult.data?.let { data ->
                        val errorBytes = data.getByteArrayExtra(Fido.FIDO2_KEY_ERROR_EXTRA)
                        if (errorBytes != null) {
                            try {
                                val errorResponse = PublicKeyCredential.deserializeFromBytes(errorBytes)
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

                    Toast.makeText(this@AnswerActivity, "Attestation canceled", Toast.LENGTH_LONG).show()
                }

                bytes == null -> {
                    Log.e(TAG, "Credential bytes are null")
                    Toast.makeText(this@AnswerActivity, "Error: No credential data", Toast.LENGTH_LONG).show()
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
                                .makeText(this@AnswerActivity, "Signature is null", Toast.LENGTH_LONG)
                                .show()
                            return
                        }
                        val msg = viewModel.message.value!!
                        val account = wallet.account.value!!
                        // Create the Liquid Extension JSON
                        val liquidExtJSON = JSONObject()
                        liquidExtJSON.put("type", "algorand")
                        liquidExtJSON.put("requestId", msg.requestId)
                        liquidExtJSON.put("address", account.address.toString())
                        liquidExtJSON.put("signature", Base64.encode(signature!!))
                        liquidExtJSON.put("device", Build.MODEL)

                        lifecycleScope.launch {
                            Log.d(TAG, "========================================")
                            Log.d(TAG, "📤 POSTING CREDENTIAL TO SERVER")
                            Log.d(TAG, "URL: ${msg.origin}/attestation/response")
                            Log.d(TAG, "========================================")
                            
                            // POST Authenticator Results to FIDO2 API
                            val attestationResponse = attestationApi
                                .postAttestationResult(
                                    msg.origin,
                                    userAgent,
                                    credential,
                                    liquidExtJSON,
                                ).await()
                            
                            Log.d(TAG, "========================================")
                            Log.d(TAG, "✅ FIDO2 REGISTRATION SUCCESSFUL!")
                            Log.d(TAG, "Server response: ${attestationResponse.code}")
                            Log.d(TAG, "Credential ID: ${credential.id}")
                            Log.d(TAG, "Account: ${account.address}")
                            Log.d(TAG, "========================================")
                            
                            runOnUiThread {
                                Toast.makeText(
                                    this@AnswerActivity,
                                    "✅ Registration successful! Credential saved.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            
                            viewModel.saveCredential(
                                this@AnswerActivity,
                                wallet.account.value!!,
                                credential,
                            )
                            Log.d(TAG, "Credential Saved to local storage")
                            
                            // 🧪 TESTING FLAG: Set to true to auto-test authentication instead of WebRTC
                            val testAuthenticationInstead = true
                            
                            if (testAuthenticationInstead) {
                                // Test mode: Skip WebRTC and test authentication
                                kotlinx.coroutines.delay(2000) // Wait 2 seconds
                                Log.d(TAG, "========================================")
                                Log.d(TAG, "🧪 TEST MODE: TESTING AUTHENTICATION")
                                Log.d(TAG, "Now attempting to authenticate with saved credential...")
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
                                                        wallet.account.value!!,
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
        
        val response =
            assertionApi
                .postAssertionOptions(msg.origin, userAgent, credential)
                .await()
        
        Log.d(TAG, "Got assertion options from server")
        
        val session = Cookie.fromResponse(response)
        session?.let { setSession(Cookie.getID(it)) }
        val publicKeyCredentialRequestOptions =
            response.body!!.toPublicKeyCredentialRequestOptions()
        
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
                        liquidExtJSON.put("requestId", viewModel.message.value!!.requestId)
                        
                        Log.d(TAG, "📤 Posting authentication assertion to server...")
                        
                        // POST Authenticator Results to FIDO2 API
                        val response =
                            assertionApi
                                .postAssertionResult(
                                    viewModel.message.value!!.origin,
                                    userAgent,
                                    credential,
                                    liquidExtJSON,
                                ).await()

                        Log.d(TAG, "========================================")
                        Log.d(TAG, "✅ AUTHENTICATION SUCCESSFUL!")
                        Log.d(TAG, "Server response: ${response.code}")
                        Log.d(TAG, "Credential was recognized and validated!")
                        Log.d(TAG, "========================================")
                        
                        runOnUiThread {
                            Toast.makeText(
                                this@AnswerActivity,
                                "🔓 Authentication Successful!\nUsing saved credential",
                                Toast.LENGTH_LONG
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
                                                    wallet.account.value!!,
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
