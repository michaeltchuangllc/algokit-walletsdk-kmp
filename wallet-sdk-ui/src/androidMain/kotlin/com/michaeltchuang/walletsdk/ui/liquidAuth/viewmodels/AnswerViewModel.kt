package com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.ServiceConnection
import android.os.Build
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.R
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.algorand.algosdk.sdk.BytesArray
import com.algorand.algosdk.sdk.Sdk
import com.algorand.algosdk.transaction.SignedTransaction
import com.algorand.algosdk.transaction.Transaction
import com.fasterxml.uuid.Generators
import com.google.android.gms.fido.fido2.api.common.PublicKeyCredential
import com.google.android.gms.fido.fido2.api.common.PublicKeyCredentialRequestOptions
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAccountAlgoBalance
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAccountMnemonic
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAlgo25SecretKey
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetFalcon24SecretKey
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetHdSeed
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetLocalAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetLocalAccounts
import com.michaeltchuang.walletsdk.core.algosdk.signAlgo25ArbitraryData
import com.michaeltchuang.walletsdk.core.algosdk.signFalcon24ArbitraryData
import com.michaeltchuang.walletsdk.core.algosdk.signHdKeyData
import com.michaeltchuang.walletsdk.core.algosdk.signHdKeyTransaction
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.foundation.utils.date.TimeProvider
import com.michaeltchuang.walletsdk.core.liquidAuth.auth.connect.SignalService
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.usecases.LogAppSignatureUseCase
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.usecases.ManageSignalServiceUseCase
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.usecases.ProcessSignTransactionsUseCase
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.usecases.ProvideHttpClientUseCase
import com.michaeltchuang.walletsdk.core.network.usecase.GetCurrentBlockUseCase
import com.michaeltchuang.walletsdk.core.passkeys.domain.model.PublicKeyCredentialCreationOptions
import com.michaeltchuang.walletsdk.core.passkeys.domain.repository.PasskeyRepository
import com.michaeltchuang.walletsdk.core.passkeys.domain.usecase.AddNewPasskey
import com.michaeltchuang.walletsdk.core.passkeys.domain.usecase.SetPasskeyLastUsedTime
import com.michaeltchuang.walletsdk.core.railmpp.MppNetworks
import com.michaeltchuang.walletsdk.core.railmpp.MppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.core.ConsentApproval
import com.michaeltchuang.walletsdk.core.railmpp.core.ConsentTerms
import com.michaeltchuang.walletsdk.core.railmpp.data.repository.AndroidSessionVaultBalanceRepository
import com.michaeltchuang.walletsdk.core.railmpp.usecases.GetRemainingSessionVaultBalanceUseCase
import com.michaeltchuang.walletsdk.core.railmpp.utils.MppPayments
import com.michaeltchuang.walletsdk.core.railmpp.utils.RailMppConstants
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases.AssertionIntentLauncherUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases.AttestationIntentLauncherUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases.HandleAssertionResultUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases.HandleAttestationResultUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases.PrepareAuthenticationUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases.ProcessBiometricTransactionSigningUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases.RegisterPasskeyUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases.SetupMppPaymentViewerUseCase
import com.michaeltchuang.walletsdk.core.utils.GoMobileDispatcher
import com.michaeltchuang.walletsdk.ui.liquidStream.utils.SESSION_LOGGED_OUT
import com.michaeltchuang.walletsdk.utils.DataResource
import foundation.algorand.crypto.EncoderType
import foundation.algorand.crypto.avm.Encoder
import foundation.algorand.provider.Message
import foundation.algorand.provider.avm.models.RequestMessage
import foundation.algorand.provider.avm.models.ResponseMessage
import foundation.algorand.provider.avm.models.SignTransactionsParams
import foundation.algorand.provider.avm.models.SignTransactionsResult
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.math.BigInteger
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.roundToLong

class AnswerViewModel(
    private val addNewPasskey: AddNewPasskey,
    private val passkeyRepository: PasskeyRepository,
    private val setPasskeyLastUsedTime: SetPasskeyLastUsedTime,
    private val getAccountMnemonic: GetAccountMnemonic,
    private val getAlgo25SecretKey: GetAlgo25SecretKey,
    private val timeProvider: TimeProvider,
    private val getFalcon24SecretKey: GetFalcon24SecretKey,
    private val getLocalAccount: GetLocalAccount,
    private val getLocalAccounts: GetLocalAccounts,
    private val getSeed: GetHdSeed,
    private val processBiometricTransactionSigningUseCase: ProcessBiometricTransactionSigningUseCase,
    private val registerPasskeyUseCase: RegisterPasskeyUseCase,
    private val prepareAuthenticationUseCase: PrepareAuthenticationUseCase,
    private val manageSignalServiceUseCase: ManageSignalServiceUseCase,
    private val processSignTransactionsUseCase: ProcessSignTransactionsUseCase,
    private val attestationIntentLauncherUseCase: AttestationIntentLauncherUseCase,
    private val assertionIntentLauncherUseCase: AssertionIntentLauncherUseCase,
    private val eventDelegate: EventDelegate<ViewEvent>,
    private val logAppSignatureUseCase: LogAppSignatureUseCase,
    private val providerHttpClientUseCase: ProvideHttpClientUseCase,
    private val getAccountAlgoBalance: GetAccountAlgoBalance,
    private val getCurrentBlockUseCase: GetCurrentBlockUseCase,
    private val setupMppPaymentViewerUseCase: SetupMppPaymentViewerUseCase,
    private val getRemainingSessionVaultBalanceUseCase: GetRemainingSessionVaultBalanceUseCase =
        GetRemainingSessionVaultBalanceUseCase(AndroidSessionVaultBalanceRepository()),
) : ViewModel(),
    EventViewModel<AnswerViewModel.ViewEvent> by eventDelegate {
    companion object {
        private const val TAG = "AnswerViewModel"
        const val NOTIFICATION_CHANNEL_ID = "NOTIFICATION_CHANNEL"
        const val SERVICE_NOTIFICATION_ID = 1000
        private const val STREAM_TIMEOUT_MS = 10000L // 10 seconds without frames = stream ended
        private const val BASE58_ALPHABET =
            "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

        private fun decodeBase58(input: String): ByteArray? {
            if (input.isEmpty()) return ByteArray(0)

            var value = BigInteger.ZERO
            val radix = BigInteger.valueOf(58L)

            for (char in input) {
                val index = BASE58_ALPHABET.indexOf(char)
                if (index < 0) return null
                value = value.multiply(radix).add(BigInteger.valueOf(index.toLong()))
            }

            val raw =
                value.toByteArray().let { bytes ->
                    if (bytes.isNotEmpty() && bytes[0] == 0.toByte()) {
                        bytes.copyOfRange(
                            1,
                            bytes.size,
                        )
                    } else {
                        bytes
                    }
                }

            val leadingZeroCount = input.takeWhile { it == '1' }.length
            return ByteArray(leadingZeroCount) + raw
        }
    }

    // State
    override val viewEvent: Flow<ViewEvent> get() = eventDelegate.viewEvent
    val userAgent: String by lazy {
        val applicationId = "com.michaeltchuang.walletsdk.demo"
        val versionName = "1.0"
        "$applicationId/$versionName (Android ${Build.VERSION.RELEASE}; ${Build.MODEL}; ${Build.BRAND})"
    }
    private val _session = MutableStateFlow(SESSION_LOGGED_OUT)
    val session: StateFlow<String> = _session
    private val _authMessage = MutableStateFlow<AuthMessage?>(null)
    val authMessage: StateFlow<AuthMessage?> = _authMessage
    private val _accountBalance: MutableStateFlow<String?> = MutableStateFlow(null)
    val accountBalance: StateFlow<String?> = _accountBalance
    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    private val uuidGenerator = Generators.timeBasedEpochRandomGenerator()
    private val providerId = uuidGenerator.generate().toString()
    private val _accountAddress = MutableStateFlow("")
    val accountAddress: StateFlow<String> = _accountAddress
    private var currentAccountType: String = "algorand"
    private val encoder =
        Encoder()
    var currentChallenge: ByteArray? = null
    var showConfirmationDialog = MutableStateFlow(false)
    private val _pendingSignTransactionsParams = MutableStateFlow<SignTransactionsParams?>(null)
    val pendingSignTransactionsParams: StateFlow<SignTransactionsParams?> =
        _pendingSignTransactionsParams
    private val _pendingSignMessage = MutableStateFlow<Message?>(null)
    val pendingSignMessage: StateFlow<Message?> = _pendingSignMessage
    private var signalServiceConnection: ServiceConnection? = null
    private val _signalService =
        MutableStateFlow<SignalService?>(
            null,
        )
    val signalService = _signalService
    private var attestationApiResponse: String? = null

    // Video streaming state
    private val _videoFrame = MutableStateFlow<VideoFrameData?>(null)
    val videoFrame: StateFlow<VideoFrameData?> = _videoFrame
    private val _lastFrameTimestamp = MutableStateFlow<Long>(0)
    val lastFrameTimestamp: StateFlow<Long> = _lastFrameTimestamp
    private val _isStreamActive = MutableStateFlow(false)
    val isStreamActive: StateFlow<Boolean> = _isStreamActive

    @Volatile
    private var hasReceivedAtLeastOneFrame = false

    @Volatile
    private var hasTimedOutCurrentStream = false

    init {
        // Monitor stream activity
        viewModelScope.launch {
            while (true) {
                val lastFrame = _lastFrameTimestamp.value
                val currentlyActive =
                    if (lastFrame == 0L) {
                        false
                    } else {
                        (System.currentTimeMillis() - lastFrame) < STREAM_TIMEOUT_MS
                    }
                _isStreamActive.value = currentlyActive

                val shouldTimeoutDisconnect =
                    hasReceivedAtLeastOneFrame &&
                        !currentlyActive &&
                        !hasTimedOutCurrentStream

                Napier.d(
                    tag = TAG,
                    message =
                        "Stream monitor - lastFrame=$lastFrame, currentlyActive=$currentlyActive, " +
                            "hasReceivedAtLeastOneFrame=$hasReceivedAtLeastOneFrame, " +
                            "hasTimedOutCurrentStream=$hasTimedOutCurrentStream, " +
                            "shouldTimeout=$shouldTimeoutDisconnect",
                )

                if (shouldTimeoutDisconnect) {
                    Napier.w(tag = TAG, message = "Stream timeout triggered - disconnecting")
                    hasTimedOutCurrentStream = true
                    clearVideoFrame()
                    _session.value = SESSION_LOGGED_OUT
                    _authMessage.value = null
                    _signalService.value?.stop()
                    val reason =
                        "Stream disconnected because no video frames were received for a few seconds. " +
                            "Please reconnect to continue watching."
                    _error.value = reason
                    eventDelegate.sendEvent(ViewEvent.ShowToast(reason))
                    eventDelegate.sendEvent(ViewEvent.StreamDisconnected(reason))
                }

                delay(500) // Check every 500ms
            }
        }
    }

    // --- Public Setters and Helpers ---
    fun setSession(cookie: String?) {
        if (cookie !== null) _session.value = cookie
    }

    fun setMessage(authMessage: AuthMessage?) {
        _authMessage.value = authMessage
    }

    fun setAccountAddress(address: String) {
        _accountAddress.value = address
        viewModelScope.launch {
            currentAccountType =
                when (getLocalAccount(address)) {
                    is LocalAccount.SeedVault -> "solana"
                    else -> "algorand"
                }
        }
    }

    fun setCount(i: Int) {
        _count.value = i
    }

    fun setError(errorMessage: String?) {
        _error.value = errorMessage
    }

    fun clearError() {
        _error.value = null
    }

    fun setVideoFrame(frame: VideoFrameData?) {
        _videoFrame.value = frame
        if (frame != null) {
            val now = System.currentTimeMillis()
            _lastFrameTimestamp.value = now
            hasReceivedAtLeastOneFrame = true
            hasTimedOutCurrentStream = false
            Napier.d(
                tag = TAG,
                message = "Frame received: ${frame.width}x${frame.height}, timestamp=$now",
            )
        }
    }

    /**
     * Check if video stream is still active (received frame within timeout)
     */
    fun isStreamActive(): Boolean {
        val lastFrame = _lastFrameTimestamp.value
        if (lastFrame == 0L) return false
        return (System.currentTimeMillis() - lastFrame) < STREAM_TIMEOUT_MS
    }

    /**
     * Clear video frame when stream ends or client disconnects
     */
    fun clearVideoFrame() {
        _videoFrame.value = null
        _lastFrameTimestamp.value = 0
        hasReceivedAtLeastOneFrame = false
    }

    fun getProvideHttpClient(): OkHttpClient = providerHttpClientUseCase.invoke()

    fun getAttestationApiResponse(): String? = attestationApiResponse

    fun setAttestationApiResponse(value: String?) {
        attestationApiResponse = value
    }

    fun logAppSignature(context: Context) {
        logAppSignatureUseCase(context, context.javaClass.simpleName)
    }

    // --- Signal Service API ---
    fun bindSignalService(context: Context) {
        signalServiceConnection = manageSignalServiceUseCase(context) { _signalService.value = it }
    }

    fun unbindSignalService(context: Context) {
        stopMppPaymentViewer()
        _signalService.value?.stop()
        signalServiceConnection?.let { manageSignalServiceUseCase.unbind(context, it) }
        _signalService.value = null
    }

    // --- Balance/AVM operations ---
    fun fetchAccountBalance() {
        viewModelScope.launch {
            try {
                val balance = getAccountAlgoBalance(_accountAddress.value)
                _accountBalance.value = balance.toString()
                println("Fetched account balance: ${balance?.toString() ?: "0"}")
            } catch (e: Exception) {
                println("Exception fetching account balance: ${e.message}")
            }
        }
    }

    // --- Credential Management ---
    suspend fun saveCredential(
        account: String,
        credential: PublicKeyCredential,
        response: String,
    ) {
        val requestOption = PublicKeyCredentialCreationOptions(response)
        addNewPasskey(
            address = account,
            requestOptions = requestOption,
            credId = credential.rawId,
        )
        Napier.d(tag = TAG, message = "✅ Credential saved to local storage")
        eventDelegate.sendEvent(ViewEvent.ShowToast("✅ Credential saved to local storage"))
    }

    suspend fun getCredentialIdByAccountAddress(accountAddress: String): String? =
        passkeyRepository.getCredentialIdByAddress(accountAddress)

    suspend fun deleteCredentialByAccountAddress(accountAddress: String) {
        val credentialId = passkeyRepository.getCredentialIdByAddress(accountAddress)
        if (credentialId != null) {
            Napier.d(
                tag = TAG,
                message = "Deleting credential: $credentialId for address: $accountAddress",
            )
            passkeyRepository.removePasskeyByCredentialId(credentialId)
        } else {
            Napier.w(
                tag = TAG,
                message = "No credential found to delete for address: $accountAddress",
            )
        }
    }

    suspend fun getAvailableAccountAddresses(): List<String> = getLocalAccounts().map { it.address }.distinct()

    fun getCredentialMessage(
        account: String,
        credential: PublicKeyCredential,
    ): JSONObject {
        val credMessage = JSONObject()
        val origin = authMessage.value?.origin
        if (origin == null) {
            Log.e(TAG, "Missing auth message origin when building credential message")
        }
        credMessage.put("address", account)
        credMessage.put("device", Build.MODEL)
        credMessage.put("origin", origin ?: "")
        credMessage.put("id", credential.id)
        credMessage.put("prevCounter", count.value)
        credMessage.put("type", "credential")
        return credMessage
    }

    // --- Mnemonic Helpers ---
    suspend fun getMnemonic(address: String): String? {
        var mnemonicValue: String? = null
        getAccountMnemonic(address).use(
            onSuccess = { mnemonic -> mnemonicValue = mnemonic.words.joinToString(" ") },
            onFailed = { _, _ -> return@use null },
        )
        return mnemonicValue
    }

    suspend fun signFido2Challenge(
        challenge: ByteArray,
        address: String,
    ): ByteArray? {
        println("DEBUG: signFido2Challenge called for address: $address")
        val localAccount =
            getLocalAccount(address) ?: run {
                println("DEBUG: getLocalAccount returned null for $address")
                return null
            }
        println("DEBUG: localAccount type: ${localAccount::class.simpleName}")
        return when (localAccount) {
            is LocalAccount.Algo25 -> {
                println("DEBUG: Algo25 account, calling getAlgo25SecretKey")
                val secretKey =
                    getAlgo25SecretKey(address) ?: run {
                        println("DEBUG: getAlgo25SecretKey returned null")
                        return null
                    }
                println("DEBUG: Got secretKey with size: ${secretKey.size}")
                val result = signAlgo25ArbitraryData(challenge, secretKey)
                println("DEBUG: signAlgo25ArbitraryData returned: ${result != null}")
                result
            }

            is LocalAccount.HdKey -> {
                val seed = getSeed(localAccount.seedId) ?: return null
                signHdKeyData(
                    challenge,
                    seed,
                    localAccount.account,
                    localAccount.change,
                    localAccount.keyIndex,
                )
            }

            is LocalAccount.Falcon24 -> {
                val privateKey = getFalcon24SecretKey(address) ?: return null
                if (challenge.isEmpty() || localAccount.publicKey.isEmpty() || privateKey.isEmpty()) {
                    println("DEBUG: signFido2Challenge skipped — empty input for Falcon24 signing")
                    return null
                }
                try {
                    signFalcon24ArbitraryData(challenge, localAccount.publicKey, privateKey)
                } catch (t: Throwable) {
                    println("DEBUG: signFalcon24ArbitraryData threw: ${t.message}")
                    null
                }
            }

            is LocalAccount.SeedVault -> {
                // Seed Vault keys are non-exportable; signing requires Seed Vault signing API flow.
                println("DEBUG: SeedVault account detected; FIDO2 challenge signing not yet implemented for SeedVault")
                null
            }

            else -> null
        }
    }

    suspend fun getFee(): String {
        val localAccount = getLocalAccount(this@AnswerViewModel.accountAddress.value)
        return when (localAccount) {
            is LocalAccount.Falcon24 -> "0.004"
            else -> "0.001"
        }
    }

    suspend fun isSeedVaultAccount(address: String): Boolean = getLocalAccount(address) is LocalAccount.SeedVault

    suspend fun getAccountTypeForFido2(address: String): String {
        val localAccount = getLocalAccount(address)
        return when (localAccount) {
            is LocalAccount.Falcon24 -> "falcon-1024"
            is LocalAccount.SeedVault -> "solana"
            else -> "algorand"
        }
    }

    suspend fun resolveLocalAccount(address: String): LocalAccount? = getLocalAccount(address)

    suspend fun resolveAlgo25SecretKey(address: String): ByteArray? = getAlgo25SecretKey(address)

    suspend fun resolveFalcon24SecretKey(address: String): ByteArray? = getFalcon24SecretKey(address)

    suspend fun resolveSeed(seedId: Int): ByteArray? = getSeed(seedId)

    suspend fun getAccountPublicKey(address: String): ByteArray {
        val localAccount = getLocalAccount(address)
        return when (localAccount) {
            is LocalAccount.Falcon24 -> localAccount.publicKey
            is LocalAccount.HdKey -> localAccount.publicKey
            is LocalAccount.Algo25 -> {
                // Get secret key and extract public key (last 32 bytes of 64-byte expanded key)
                val secretKey = getAlgo25SecretKey(address)
                if (secretKey != null && secretKey.size == 64) {
                    secretKey.copyOfRange(32, 64) // Last 32 bytes are the public key
                } else {
                    ByteArray(0)
                }
            }

            is LocalAccount.SeedVault -> {
                val decoded = decodeBase58(localAccount.publicKey)
                if (decoded == null || decoded.size != 32) {
                    Napier.e(
                        tag = TAG,
                        message = "Invalid SeedVault public key format for address=${localAccount.address}, decodedLength=${decoded?.size}",
                    )
                    ByteArray(0)
                } else {
                    decoded
                }
            }

            else -> ByteArray(0)
        }
    }

    // --- AVM & DataChannel Message Logic ---
    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeUnsignedTransaction(unsignedTxn: String): Transaction? =
        com.algorand.algosdk.util.Encoder
            .decodeFromMsgPack(Base64.Default.decode(unsignedTxn), Transaction::class.java)

    @OptIn(ExperimentalEncodingApi::class)
    fun handleMessages(
        msgStr: String,
        onSignTransaction: ((SignTransactionsParams, Message) -> Unit)? = null,
        onVideoFrame: ((VideoFrameData) -> Unit)? = null,
    ) {
        try {
            Napier.d(tag = TAG, message = "Received DataChannel Message length: ${msgStr.length}")

            // Handle plain JSON messages first (not Base64/CBOR).
            if (msgStr.trimStart().startsWith("{")) {
                val json = JSONObject(msgStr)
                val reference = json.optString("reference")
                when (reference) {
                    "liquid:video:frame" -> {
                        Napier.d(tag = TAG, message = "🎥 Video frame JSON message detected")
                        handleVideoFrameMessage(msgStr, onVideoFrame)
                    }

                    "liquid:payment:balance",
                    "liquid:payment:voucher",
                    "liquid:payment:depleted",
                    -> {
                        Napier.d(
                            tag = TAG,
                            message = "💳 Liquid payment JSON message detected: $reference",
                        )
                    }

                    else -> {
                        Napier.w(
                            tag = TAG,
                            message = "⚠️ Unknown JSON message reference: $reference",
                        )
                    }
                }
                return
            }

            val cborBytes =
                Base64.Default.UrlSafe
                    .withPadding(Base64.PaddingOption.ABSENT)
                    .decode(msgStr)

            // Log first bytes to verify incoming CBOR encoding type
            if (cborBytes.isNotEmpty()) {
                val firstBytes = cborBytes.take(10).joinToString(" ") { "0x%02X".format(it) }
                Napier.d(tag = TAG, message = "Incoming CBOR first bytes: $firstBytes")
                Napier.d(
                    tag = TAG,
                    message =
                        "Incoming CBOR encoding: " +
                            if (cborBytes[0].toInt() and 0x1F == 0x1F) {
                                "INDEFINITE-LENGTH"
                            } else {
                                "DEFINITE-LENGTH"
                            },
                )
            }

            val message = Message(cborBytes, EncoderType.CBOR)
            val request = encoder.decode<RequestMessage>(message.data, message.encoding)
            Napier.d(tag = TAG, message = "Message decoded - Reference: ${request.reference}")
            Napier.d(tag = TAG, message = "Request ID: ${request.id}")

            when (request.reference) {
                "arc0027:sign_transactions:request" -> {
                    Napier.d(tag = TAG, message = "✅ Transaction signing request detected")
                    viewModelScope.launch {
                        val params =
                            encoder.decode<SignTransactionsParams>(
                                encoder.encode(
                                    request.params,
                                    EncoderType.NONE,
                                ),
                                EncoderType.NONE,
                            )
                        Napier.d(
                            tag = TAG,
                            message = "Decoded ${params.txns.size} transaction(s) from request",
                        )
                        Napier.d(tag = TAG, message = "Provider ID: ${params.providerId}")
                        _pendingSignTransactionsParams.value = params
                        _pendingSignMessage.value = message
                        showConfirmationDialog.value = true
                        onSignTransaction?.invoke(params, message)
                    }
                }

                "liquid:video:frame" -> {
                    Napier.d(tag = TAG, message = "🎥 Video frame message detected (CBOR encoded)")
                    // Some peers may send ARC-style video-frame references via CBOR envelope.
                    // Fall back to JSON parser using original payload so viewer still renders frames.
                    handleVideoFrameMessage(msgStr, onVideoFrame)
                }

                else -> {
                    Napier.w(
                        tag = TAG,
                        message = "⚠️ Unknown request reference: ${request.reference}",
                    )
                }
            }
        } catch (e: Throwable) {
            Napier.e(tag = TAG, message = "❌ Error handling message: $e")
            e.printStackTrace()
        }
    }

    /**
     * Handle video frame messages from broadcaster
     */
    private fun handleVideoFrameMessage(
        msgStr: String,
        onVideoFrame: ((VideoFrameData) -> Unit)?,
    ) {
        try {
            val json = JSONObject(msgStr)
            val dataBase64 = json.getString("data")
            val frameData =
                java.util.Base64
                    .getDecoder()
                    .decode(dataBase64)

            val videoFrame =
                VideoFrameData(
                    id = json.getString("id"),
                    timestamp = json.getLong("timestamp"),
                    data = frameData,
                    width = json.getInt("width"),
                    height = json.getInt("height"),
                    format = json.optString("format", "jpeg"),
                )

            Napier.d(
                tag = TAG,
                message = "🎥 Video frame decoded: ${videoFrame.width}x${videoFrame.height}, ${frameData.size} bytes",
            )
            onVideoFrame?.invoke(videoFrame)
        } catch (e: Exception) {
            Napier.e(tag = TAG, message = "❌ Failed to decode video frame: $e")
        }
    }

    // MPP consent request bridge for UI-driven approval dialog
    private val _pendingMppConsent = MutableStateFlow<ConsentTerms?>(null)
    val pendingMppConsent: StateFlow<ConsentTerms?> = _pendingMppConsent

    private val _viewerSessionVaultMicroUsdc = MutableStateFlow(0L)
    val viewerSessionVaultMicroUsdc: StateFlow<Long> = _viewerSessionVaultMicroUsdc
    private val _viewerProgressBalanceMicroUsdc = MutableStateFlow(0L)
    val viewerProgressBalanceMicroUsdc: StateFlow<Long> = _viewerProgressBalanceMicroUsdc
    private val _currentBlockNumber = MutableStateFlow<Long?>(null)
    val currentBlockNumber: StateFlow<Long?> = _currentBlockNumber
    private var blockNumberPollingJob: Job? = null

    @Volatile
    private var pendingMppConsentContinuation: CompletableDeferred<ConsentApproval>? = null

    /**
     * Encode ResponseMessage to CBOR bytes
     */
    fun encodeResponseMessage(responseMessage: ResponseMessage): ByteArray = encoder.encode(responseMessage, EncoderType.CBOR)

    fun handleMessage(message: Message): Any {
        val decoded = encoder.decode<RequestMessage>(message.data, message.encoding)
        when (decoded.reference) {
            "arc0027:sign_transactions:request" -> {
                val params =
                    encoder.decode<SignTransactionsParams>(
                        encoder.encode(
                            decoded.params,
                            EncoderType.NONE,
                        ),
                        EncoderType.NONE,
                    )
                val result = runBlocking { processSignTransactions(params) }
                return ResponseMessage(
                    id = uuidGenerator.generate().toString(),
                    reference = "arc0027:sign_transactions:response",
                    requestId = decoded.id,
                    result = result,
                )
            }

            else -> throw IllegalArgumentException("Invalid reference: ${decoded.reference}")
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun processSignTransactions(params: SignTransactionsParams): SignTransactionsResult =
        processSignTransactionsUseCase(
            params = params,
            providerId = providerId,
            accountAddress = _accountAddress.value,
        )

    fun clearPendingSignRequest() {
        _pendingSignTransactionsParams.value = null
        _pendingSignMessage.value = null
        showConfirmationDialog.value = false
    }

    fun consumeViewerRuntimeStateForUi() {
        // Keep runtime state in ViewModel so UI can be detached from activity recreation.
        // This method exists as an explicit phase-2 handoff marker for callers.
    }

    suspend fun requestMppConsentFromUi(terms: ConsentTerms): ConsentApproval {
        Log.e(TAG, "[VIEWER_MPP_CONSENT_REQUEST] amount=${terms.amount} asset=${terms.asset} network=${terms.network} gating=${terms.gatingMode}")
        val deferred = CompletableDeferred<ConsentApproval>()
        pendingMppConsentContinuation = deferred
        _pendingMppConsent.value = terms
        return try {
            val approval = deferred.await()
            Log.e(TAG, "[VIEWER_MPP_CONSENT_RESOLVED] approved=${approval.approved}")
            approval
        } finally {
            pendingMppConsentContinuation = null
            _pendingMppConsent.value = null
        }
    }

    fun approveMppConsent(approval: ConsentApproval) {
        // Do not seed viewer balance from consent budget; source of truth is on-chain vault read.
        if (approval.approved) {
            _viewerSessionVaultMicroUsdc.value = 0L
            _viewerProgressBalanceMicroUsdc.value = 0L
        }
        pendingMppConsentContinuation?.complete(approval)
    }

    fun setViewerSessionVaultProgress(
        remainingBalanceMicroUsdc: Long,
        progressBalanceMicroUsdc: Long,
    ) {
        _viewerSessionVaultMicroUsdc.value = remainingBalanceMicroUsdc.coerceAtLeast(0L)
        _viewerProgressBalanceMicroUsdc.value = progressBalanceMicroUsdc.coerceAtLeast(0L)
    }

    fun setViewerSessionVaultBalance(balanceMicroUsdc: Long) {
        setViewerSessionVaultProgress(
            remainingBalanceMicroUsdc = balanceMicroUsdc,
            progressBalanceMicroUsdc = balanceMicroUsdc,
        )
    }

    fun rejectMppConsent() {
        Log.e(TAG, "[VIEWER_MPP_CONSENT_REJECTED]")
        pendingMppConsentContinuation?.complete(
            ConsentApproval(
                approved = false,
                autoPaySegments = false,
            ),
        )
    }


    suspend fun topUpViewerSessionVault(
        enteredAmount: String,
        viewerAddress: String,
        creatorAddress: String,
        signer: MppWalletSigner,
    ): Result<Long?> {
        val amountUsdc = enteredAmount.toDoubleOrNull()?.takeIf { it > 0.0 } ?: 1.0
        val depositMicroUsdc = (amountUsdc * 1_000_000.0).roundToLong().coerceAtLeast(1L)

        return runCatching {
            val txId =
                MppPayments
                    .topUpSessionVault(
                        signer = signer,
                        viewerAddress = viewerAddress,
                        hostAddress = creatorAddress,
                        additionalDepositMicroUsdc = depositMicroUsdc,
                        appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                    ).getOrThrow()
            Log.e(
                TAG,
                "[VIEWER_SESSION_VAULT_TOPUP_OK] viewer=$viewerAddress creator=$creatorAddress amountMicroUsdc=$depositMicroUsdc txId=$txId",
            )

            val onChainRemaining =
                getRemainingSessionVaultBalanceUseCase(
                    GetRemainingSessionVaultBalanceUseCase.Params(
                        viewerAddress = viewerAddress,
                        hostAddress = creatorAddress,
                        appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                        authorizedSignerPublicKey = signer.authorizedSignerPublicKey,
                    ),
                ).getOrThrow()

            setViewerSessionVaultBalance(onChainRemaining)

            onChainRemaining
        }.onFailure { throwable ->
            Log.e(
                TAG,
                "[VIEWER_SESSION_VAULT_TOPUP_ERR] viewer=$viewerAddress creator=$creatorAddress amountMicroUsdc=$depositMicroUsdc",
                throwable,
            )
        }
    }

    /**
     * Build an [MppWalletSigner] for the given account address.
     * Supports Algo25, HdKey, and Falcon24 local accounts.
     */
    suspend fun buildMppWalletSigner(address: String): MppWalletSigner? {
        val localAccount = getLocalAccount(address) ?: return null
        if (localAccount is LocalAccount.SeedVault) return null

        val authorizedSignerPublicKey = getAccountPublicKey(address)

        return object : MppWalletSigner {
            override val address: String = address
            override val authorizedSignerPublicKey: ByteArray = authorizedSignerPublicKey
            override val signerType: Long = if (localAccount is LocalAccount.Falcon24) 1L else 0L

            override suspend fun signTransaction(txn: Transaction): ByteArray {
                return try {
                    when (localAccount) {
                        is LocalAccount.Algo25 -> {
                            val secretKey = getAlgo25SecretKey(address)
                            if (secretKey == null) {
                                Log.e(TAG, "Missing Algo25 key for $address")
                                return ByteArray(0)
                            }
                            val txnBytes =
                                com.algorand.algosdk.util.Encoder
                                    .encodeToMsgPack(txn)
                            val signature = signAlgo25ArbitraryData(txn.bytesToSign(), secretKey)
                            if (signature == null) {
                                Log.e(TAG, "Algo25 arbitrary signing failed for $address")
                                return ByteArray(0)
                            }
                            withContext(GoMobileDispatcher.dispatcher) {
                                Sdk.attachSignature(signature, txnBytes)
                            }
                        }

                        is LocalAccount.HdKey -> {
                            val seed = getSeed(localAccount.seedId)
                            if (seed == null) {
                                Log.e(TAG, "Missing HD seed for $address")
                                return ByteArray(0)
                            }
                            signHdKeyTransaction(
                                transactionByteArray =
                                    com.algorand.algosdk.util.Encoder.encodeToMsgPack(
                                        txn,
                                    ),
                                seed = seed,
                                account = localAccount.account,
                                change = localAccount.change,
                                key = localAccount.keyIndex,
                            ) ?: run {
                                Log.e(TAG, "HD signing failed for $address")
                                return ByteArray(0)
                            }
                        }

                        is LocalAccount.Falcon24 -> {
                            val secretKey = getFalcon24SecretKey(address)
                            if (secretKey == null) {
                                Log.e(TAG, "Missing Falcon24 key for $address")
                                return ByteArray(0)
                            }
                            signFalconTxnFromBundle(
                                txn = txn,
                                publicKey = localAccount.publicKey,
                                privateKey = secretKey,
                            )
                        }

                        else -> {
                            Log.e(
                                TAG,
                                "Unsupported account for MPP wallet signing: ${localAccount::class.simpleName}",
                            )
                            ByteArray(0)
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "signTransaction failed for $address", t)
                    ByteArray(0)
                }
            }

            override suspend fun signTransactions(txns: List<Transaction>): List<ByteArray> {
                return try {
                    when (localAccount) {
                        is LocalAccount.Falcon24 -> {
                            val secretKey = getFalcon24SecretKey(address)
                            if (secretKey == null) {
                                Log.e(TAG, "Missing Falcon24 key for $address")
                                return emptyList()
                            }
                            signFalconTxnGroupFromBundle(
                                txns = txns,
                                publicKey = localAccount.publicKey,
                                privateKey = secretKey,
                            )
                        }

                        else -> super.signTransactions(txns)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "signTransactions failed for $address", t)
                    emptyList()
                }
            }
        }
    }

    private suspend fun signFalconTxnFromBundle(
        txn: Transaction,
        publicKey: ByteArray,
        privateKey: ByteArray,
    ): ByteArray {
        val signed =
            signFalconTxnGroupFromBundle(
                txns = listOf(txn),
                publicKey = publicKey,
                privateKey = privateKey,
            )
        return signed.firstOrNull() ?: run {
            Log.e(TAG, "Falcon bundle returned no signed txn")
            ByteArray(0)
        }
    }

    private fun decodeFalconBundlePiece(encoded: String): ByteArray? {
        val trimmed = encoded.trim()
        if (trimmed.isEmpty()) return null

        fun addPadding(s: String): String {
            val rem = s.length % 4
            return if (rem == 0) s else s + "=".repeat(4 - rem)
        }

        val candidates =
            listOf(trimmed, addPadding(trimmed))
                .flatMap { value ->
                    listOf(value, value.replace('+', '-').replace('/', '_'))
                }.distinct()

        candidates.forEach { candidate ->
            runCatching {
                java.util.Base64
                    .getDecoder()
                    .decode(candidate)
            }.getOrNull()?.let { return it }
            runCatching {
                java.util.Base64
                    .getUrlDecoder()
                    .decode(candidate)
            }.getOrNull()?.let { return it }
            runCatching { Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT).decode(candidate) }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun matchesExpectedTransaction(
        expected: Transaction,
        actual: Transaction,
    ): Boolean {
        if (expected.type?.toString() != actual.type?.toString()) return false
        if (expected.sender?.toString() != actual.sender?.toString()) return false

        return when (expected.type?.toString()) {
            "pay" -> {
                expected.receiver?.toString() == actual.receiver?.toString() &&
                    (expected.amount ?: java.math.BigInteger.ZERO) == (actual.amount ?: java.math.BigInteger.ZERO)
            }
            "axfer" -> {
                expected.assetReceiver?.toString() == actual.assetReceiver?.toString() &&
                    (expected.assetAmount ?: java.math.BigInteger.ZERO) == (actual.assetAmount ?: java.math.BigInteger.ZERO) &&
                    expected.assetIndex.toLong() == actual.assetIndex.toLong()
            }
            "appl" -> {
                expected.applicationId.toLong() == actual.applicationId.toLong() &&
                    (expected.applicationArgs ?: emptyList<ByteArray>()) == (actual.applicationArgs ?: emptyList<ByteArray>())
            }
            else -> true
        }
    }

    private suspend fun signFalconTxnGroupFromBundle(
        txns: List<Transaction>,
        publicKey: ByteArray,
        privateKey: ByteArray,
    ): List<ByteArray> {
        if (txns.isEmpty()) return emptyList()
        if (publicKey.isEmpty() || privateKey.isEmpty()) {
            Log.e(TAG, "[FALCON_BUNDLE_SKIP] reason=empty_key publicKeyLen=${publicKey.size} privateKeyLen=${privateKey.size}")
            return emptyList()
        }

        Log.e(
            TAG,
            "[FALCON_BUNDLE_TRACE] inputTxnCount=${txns.size} firstGroup=${txns.firstOrNull()?.group}",
        )

        // Dispatch the entire BytesArray construction + Go call onto the dedicated
        // GoMobile OS thread.  Go's GC write-barrier requires pointer-containing types
        // (like BytesArray.v [][]byte) to be at 8-byte-aligned addresses.  Thread-pool
        // threads may call Go from a context not yet initialised by the Go runtime,
        // triggering "fatal error: bulkBarrierPreWrite: unaligned arguments".
        // A single persistent OS thread ensures consistent, properly-aligned execution.
        return withContext(GoMobileDispatcher.dispatcher) {
            val expectedTxns =
                txns.map {
                    com.algorand.algosdk.util.Encoder
                        .encodeToMsgPack(it)
                }
            val expectedTxIds = txns.map { it.txID() }
            val txnList = BytesArray().apply { expectedTxns.forEach { append(it.copyOf()) } }
            val resultCsv =
                try {
                    Sdk.signFalconBundle(
                        txnList,
                        publicKey.copyOf(),
                        privateKey.copyOf(),
                    )
                } catch (t: Throwable) {
                    Log.e(TAG, "[FALCON_BUNDLE_SIGN_FAILED] error=${t.message}", t)
                    return@withContext emptyList()
                }

            val rawSigned =
                resultCsv
                    .split(",")
                    .filter { it.isNotBlank() }
                    .mapNotNull { decodeFalconBundlePiece(it) }

            val decodedSigned =
                rawSigned
                    .mapNotNull { signedBytes ->
                        runCatching {
                            val signed =
                                com.algorand.algosdk.util.Encoder
                                    .decodeFromMsgPack(signedBytes, SignedTransaction::class.java)
                            val signedTxn = signed.tx ?: return@runCatching null
                            Triple(signedTxn.txID(), signedTxn, signedBytes)
                        }.getOrNull()
                    }

            val expectedFirstGroup = txns.firstOrNull()?.group?.toString()
            val decodedFirstGroup =
                decodedSigned
                    .firstOrNull()
                    ?.second
                    ?.group
                    ?.toString()
            val decodedAllGrouped =
                decodedSigned.all {
                    it.second.group != null &&
                        it.second.group
                            .toString()
                            .isNotBlank()
                }

            Log.e(
                TAG,
                "[FALCON_BUNDLE_TRACE] rawSignedCount=${rawSigned.size} decodedSignedCount=${decodedSigned.size} expectedTxnCount=${txns.size} expectedFirstGroup=$expectedFirstGroup decodedFirstGroup=$decodedFirstGroup decodedAllGrouped=$decodedAllGrouped",
            )

            // Go signer behavior: if incoming txns have no group ID, it may inject dummies and return expanded group.
            // In that mode we must return the full signed set for broadcast, not only the requested subset.
            if (txns.firstOrNull()?.group == null ||
                txns
                    .firstOrNull()
                    ?.group
                    .toString()
                    .isBlank()
            ) {
                if (rawSigned.size > txns.size) {
                    Log.e(
                        TAG,
                        "[FALCON_BUNDLE_TRACE] returningRawSigned=true returnedCount=${rawSigned.size}",
                    )
                    return@withContext rawSigned
                }
            }

            val remaining = decodedSigned.toMutableList()
            val out = mutableListOf<ByteArray>()

            expectedTxIds.forEachIndexed { index, expectedTxId ->
                val txIdMatchIndex = remaining.indexOfFirst { it.first == expectedTxId }
                if (txIdMatchIndex >= 0) {
                    out += remaining.removeAt(txIdMatchIndex).third
                } else {
                    val expectedTxn = txns[index]
                    val semanticMatchIndex =
                        remaining.indexOfFirst { (_, actualTxn, _) ->
                            matchesExpectedTransaction(expectedTxn, actualTxn)
                        }
                    if (semanticMatchIndex >= 0) {
                        out += remaining.removeAt(semanticMatchIndex).third
                    } else {
                        Log.e(
                            TAG,
                            "Falcon bundle missing signed txn for grouped request txId=$expectedTxId",
                        )
                        return@withContext emptyList()
                    }
                }
            }

            Log.e(
                TAG,
                "[FALCON_BUNDLE_TRACE] returningFiltered=true returnedCount=${out.size} filteredOut=${rawSigned.size - out.size}",
            )
            out
        }
    }

    fun startRealtimeBlockNumberUpdates() {
        if (blockNumberPollingJob?.isActive == true) return
        blockNumberPollingJob =
            viewModelScope.launch {
                while (true) {
                    getCurrentBlockUseCase().collect { result ->
                        when (result) {
                            is DataResource.Success -> {
                                _currentBlockNumber.value = result.data
                            }

                            is DataResource.Error,
                            is DataResource.Loading,
                            -> Unit
                        }
                    }
                    delay(1000)
                }
            }
    }

    fun stopRealtimeBlockNumberUpdates() {
        blockNumberPollingJob?.cancel()
        blockNumberPollingJob = null
    }

    fun setupMppPaymentViewer(
        viewerAddress: String?,
        hostAddress: String? = null,
    ) {
        setupMppPaymentViewerUseCase(
            SetupMppPaymentViewerUseCase.Params(
                signalService = signalService.value,
                viewerAddress = viewerAddress,
                hostAddress = hostAddress,
                scope = viewModelScope,
                buildMppWalletSigner = ::buildMppWalletSigner,
                resolveMppClientNetwork = ::resolveMppClientNetwork,
                requestMppConsent = ::requestMppConsentFromUi,
                setViewerSessionVaultProgress = ::setViewerSessionVaultProgress,
                signFido2Challenge = ::signFido2Challenge,
            ),
        )
    }

    fun startViewerOnChainRefresh(
        viewerAddress: String,
        hostAddress: String? = null,
    ) {
        setupMppPaymentViewerUseCase.startViewerOnChainRefresh(
            scope = viewModelScope,
            viewerAddress = viewerAddress,
            hostAddress = hostAddress,
            authorizedSignerPublicKey = null,
            setViewerSessionVaultProgress = ::setViewerSessionVaultProgress,
        )
    }

    fun stopMppPaymentViewer() {
        setupMppPaymentViewerUseCase.stop()
    }

    private suspend fun resolveMppClientNetwork(address: String): String =
        if (getLocalAccount(address) is LocalAccount.SeedVault) {
            MppNetworks.SOLANA_DEVNET
        } else {
            MppNetworks.ALGORAND_TESTNET
        }

    suspend fun processBiometricTransactionSigning(
        activity: FragmentActivity,
        params: SignTransactionsParams,
        message: Message,
    ) {
        when (
            val result =
                processBiometricTransactionSigningUseCase(
                    activity = activity,
                    viewModel = this,
                    params = params,
                    message = message,
                )
        ) {
            is ProcessBiometricTransactionSigningUseCase.Result.Success -> {
                eventDelegate.sendEvent(
                    ViewEvent.TransactionSigned(
                        result.resultMessage,
                        result.signResult,
                    ),
                )
            }

            is ProcessBiometricTransactionSigningUseCase.Result.Cancelled ->
                eventDelegate.sendEvent(
                    ViewEvent.ShowToast(result.reason),
                )

            is ProcessBiometricTransactionSigningUseCase.Result.Error ->
                eventDelegate.sendEvent(
                    ViewEvent.ShowError(result.message),
                )
        }
    }

    suspend fun preparePasskeyRegistration(
        authMessage: AuthMessage,
        accountAddress: String,
        options: JSONObject = JSONObject(),
        onSessionUpdate: (String?) -> Unit = {},
    ): RegisterPasskeyUseCase.Result {
        val result =
            registerPasskeyUseCase(
                authMessage = authMessage,
                algoAddress = accountAddress,
                viewModel = this,
                options = options,
                onSessionUpdate = onSessionUpdate,
            )
        when (result) {
            is RegisterPasskeyUseCase.Result.Success -> { // NOOP: navigation handled by caller
            }

            is RegisterPasskeyUseCase.Result.Error ->
                eventDelegate.sendEvent(
                    ViewEvent.ShowError(
                        result.message,
                    ),
                )
        }
        return result
    }

    fun registerPasskey(
        authMessage: AuthMessage,
        accountAddress: String,
        options: JSONObject = JSONObject(),
    ) {
        viewModelScope.launch {
            val result =
                preparePasskeyRegistration(
                    authMessage,
                    accountAddress,
                    options,
                    onSessionUpdate = { sessionId -> sessionId?.let { setSession(it) } },
                )
            when (result) {
                is RegisterPasskeyUseCase.Result.Success -> {
                    setAttestationApiResponse(result.attestationApiResponse)
                    eventDelegate.sendEvent(
                        ViewEvent.RegistrationSuccess(
                            result.pubKeyCredentialCreationOptions,
                            accountAddress,
                        ),
                    )
                }

                is RegisterPasskeyUseCase.Result.Error ->
                    eventDelegate.sendEvent(
                        ViewEvent.ShowError(
                            result.message,
                        ),
                    )
            }
        }
    }

    suspend fun prepareAuthentication(
        authMessage: AuthMessage,
        credentialId: String,
        onSessionUpdate: (String?) -> Unit = {},
        onCredentialNotFound: () -> Unit = {},
    ): PrepareAuthenticationUseCase.Result {
        val result =
            prepareAuthenticationUseCase(
                authMessage = authMessage,
                credentialId = credentialId,
                viewModel = this,
                onSessionUpdate = onSessionUpdate,
                onCredentialNotFound = onCredentialNotFound,
            )
        return result
    }

    fun authenticate(
        authMessage: AuthMessage,
        credentialId: String,
        setSession: ((String?) -> Unit)? = null,
        onCredentialNotFound: (() -> Unit)? = null,
    ) {
        viewModelScope.launch {
            val result =
                prepareAuthentication(
                    authMessage,
                    credentialId,
                    onSessionUpdate = { sessionId -> setSession?.invoke(sessionId) },
                    onCredentialNotFound = { onCredentialNotFound?.invoke() },
                )
            when (result) {
                is PrepareAuthenticationUseCase.Result.Success -> {
                    eventDelegate.sendEvent(
                        ViewEvent.AuthenticationSuccess(
                            result.publicKeyCredentialRequestOptions,
                            credentialId,
                        ),
                    )
                    setPasskeyLastUsedTime(credentialId, timeProvider.getCurrentTimeMillis())
                }

                is PrepareAuthenticationUseCase.Result.CredentialNotFound ->
                    eventDelegate.sendEvent(
                        ViewEvent.ShowError(result.message),
                    )

                is PrepareAuthenticationUseCase.Result.Error ->
                    eventDelegate.sendEvent(
                        ViewEvent.ShowError(
                            result.message,
                        ),
                    )
            }
        }
    }

    fun getAttestationIntentLauncher(
        activity: AppCompatActivity,
        callback: (HandleAttestationResultUseCase.Result) -> Unit,
    ): ActivityResultLauncher<IntentSenderRequest> = attestationIntentLauncherUseCase(activity, this, callback)

    fun getAssertionIntentLauncher(
        activity: AppCompatActivity,
        callback: (HandleAssertionResultUseCase.Result) -> Unit,
    ): ActivityResultLauncher<IntentSenderRequest> = assertionIntentLauncherUseCase(activity, this, callback)

    fun handleAssertionResultFromLauncher(result: HandleAssertionResultUseCase.Result) {
        viewModelScope.launch {
            when (result) {
                is HandleAssertionResultUseCase.Result.Success -> {
                    eventDelegate.sendEvent(ViewEvent.ShowToast("Authentication Successful!"))
                    eventDelegate.sendEvent(ViewEvent.AssertionSuccess(result.credential))
                }

                is HandleAssertionResultUseCase.Result.Cancelled ->
                    eventDelegate.sendEvent(
                        ViewEvent.ShowToast(result.message),
                    )

                is HandleAssertionResultUseCase.Result.Error ->
                    eventDelegate.sendEvent(
                        ViewEvent.ShowError(
                            result.message,
                        ),
                    )
            }
        }
    }

    fun handleAttestationResultFromLauncher(
        result: HandleAttestationResultUseCase.Result,
        accountAddress: String?,
    ) {
        when (result) {
            is HandleAttestationResultUseCase.Result.Success -> {
                val apiResponse = getAttestationApiResponse()
                if (accountAddress != null && apiResponse != null) {
                    viewModelScope.launch {
                        saveCredential(
                            account = accountAddress,
                            credential = result.credential,
                            response = apiResponse,
                        )
                        eventDelegate.sendEvent(ViewEvent.AttestationSuccess(result.credential))
                    }
                } else {
                    viewModelScope.launch {
                        eventDelegate.sendEvent(
                            ViewEvent.AttestationError("Missing account or API response for credential save"),
                        )
                    }
                }
            }

            is HandleAttestationResultUseCase.Result.Cancelled ->
                viewModelScope.launch {
                    eventDelegate.sendEvent(
                        ViewEvent.AttestationCancelled,
                    )
                }

            is HandleAttestationResultUseCase.Result.Error ->
                viewModelScope.launch {
                    eventDelegate.sendEvent(
                        ViewEvent.AttestationError(result.message),
                    )
                }
        }
    }

    fun createChannels(notificationManager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "WebRTC Service",
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun createNotificationBuilder(
        context: Context,
        contentText: String = "Tap to open the app.",
        contentTitle: String = "Liquid Auth",
        channelId: String = NOTIFICATION_CHANNEL_ID,
    ): NotificationCompat.Builder =
        NotificationCompat
            .Builder(context, channelId)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setColor(ContextCompat.getColor(context, R.color.biometric_error_color))
            .setSmallIcon(com.michaeltchuang.walletsdk.ui.R.drawable.ic_key)

    // --- ViewEvents ---
    sealed interface ViewEvent {
        data class ShowToast(
            val message: String,
        ) : ViewEvent

        data class TransactionSigned(
            val resultMessage: ResponseMessage,
            val signResult: SignTransactionsResult,
        ) : ViewEvent

        data class ShowError(
            val message: String,
        ) : ViewEvent

        data class AttestationSuccess(
            val credential: PublicKeyCredential,
        ) : ViewEvent

        object AttestationCancelled : ViewEvent

        data class AttestationError(
            val message: String,
        ) : ViewEvent

        data class AssertionSuccess(
            val credential: PublicKeyCredential,
        ) : ViewEvent

        data class AuthenticationSuccess(
            val publicKeyCredentialRequestOptions: PublicKeyCredentialRequestOptions,
            val credentialId: String,
        ) : ViewEvent

        data class RegistrationSuccess(
            val pubKeyCredentialCreationOptions: com.google.android.gms.fido.fido2.api.common.PublicKeyCredentialCreationOptions,
            val accountAddress: String,
        ) : ViewEvent

        data class VideoFrameReceived(
            val frame: VideoFrameData,
        ) : ViewEvent

        data class StreamDisconnected(
            val reason: String,
        ) : ViewEvent
    }

    /**
     * Video frame data class for streaming camera feed
     */
    data class VideoFrameData(
        val id: String,
        val timestamp: Long,
        val data: ByteArray,
        val width: Int,
        val height: Int,
        val format: String = "jpeg",
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as VideoFrameData

            if (id != other.id) return false
            if (timestamp != other.timestamp) return false
            if (!data.contentEquals(other.data)) return false
            if (width != other.width) return false
            if (height != other.height) return false
            if (format != other.format) return false

            return true
        }

        override fun hashCode(): Int {
            var result = id.hashCode()
            result = 31 * result + timestamp.hashCode()
            result = 31 * result + data.contentHashCode()
            result = 31 * result + width
            result = 31 * result + height
            result = 31 * result + format.hashCode()
            return result
        }
    }
}
