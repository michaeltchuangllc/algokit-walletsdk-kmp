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
import com.algorand.algosdk.sdk.Sdk
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
import com.michaeltchuang.walletsdk.core.algosdk.signFalcon24Transaction
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
import com.michaeltchuang.walletsdk.core.railmpp.MppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.core.ConsentApproval
import com.michaeltchuang.walletsdk.core.railmpp.core.ConsentTerms
import com.michaeltchuang.walletsdk.core.railmpp.utils.MppPayments
import com.michaeltchuang.walletsdk.core.railmpp.utils.RailMppConstants
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases.AssertionIntentLauncherUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases.AttestationIntentLauncherUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases.HandleAssertionResultUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases.HandleAttestationResultUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases.PrepareAuthenticationUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases.ProcessBiometricTransactionSigningUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases.RegisterPasskeyUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.utils.SESSION_LOGGED_OUT
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
) : ViewModel(),
    EventViewModel<AnswerViewModel.ViewEvent> by eventDelegate {
    companion object {
        private const val TAG = "AnswerViewModel"
        const val NOTIFICATION_CHANNEL_ID = "NOTIFICATION_CHANNEL"
        const val SERVICE_NOTIFICATION_ID = 1000
        private const val STREAM_TIMEOUT_MS = 10000L // 10 seconds without frames = stream ended
        private const val BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

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
                    if (bytes.isNotEmpty() && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
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
    val pendingSignTransactionsParams: StateFlow<SignTransactionsParams?> = _pendingSignTransactionsParams
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
            Napier.d(tag = TAG, message = "Frame received: ${frame.width}x${frame.height}, timestamp=$now")
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
            Napier.d(tag = TAG, message = "Deleting credential: $credentialId for address: $accountAddress")
            passkeyRepository.removePasskeyByCredentialId(credentialId)
        } else {
            Napier.w(tag = TAG, message = "No credential found to delete for address: $accountAddress")
        }
    }

    suspend fun getAvailableAccountAddresses(): List<String> = getLocalAccounts().map { it.address }.distinct()

    fun getCredentialMessage(
        account: String,
        credential: PublicKeyCredential,
    ): JSONObject {
        val credMessage = JSONObject()
        credMessage.put("address", account)
        credMessage.put("device", Build.MODEL)
        credMessage.put("origin", authMessage.value!!.origin)
        credMessage.put("id", credential.id)
        credMessage.put("prevCounter", count.value!!)
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
                signFalcon24ArbitraryData(challenge, localAccount.publicKey, privateKey)
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
                        Napier.d(tag = TAG, message = "💳 Liquid payment JSON message detected: $reference")
                    }
                    else -> {
                        Napier.w(tag = TAG, message = "⚠️ Unknown JSON message reference: $reference")
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
                        Napier.d(tag = TAG, message = "Decoded ${params.txns.size} transaction(s) from request")
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
                    Napier.w(tag = TAG, message = "⚠️ Unknown request reference: ${request.reference}")
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

            Napier.d(tag = TAG, message = "🎥 Video frame decoded: ${videoFrame.width}x${videoFrame.height}, ${frameData.size} bytes")
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
    private val _viewerVoucherUsageMicroUsdc = MutableStateFlow(0L)
    val viewerVoucherUsageMicroUsdc: StateFlow<Long> = _viewerVoucherUsageMicroUsdc
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
        val deferred = CompletableDeferred<ConsentApproval>()
        pendingMppConsentContinuation = deferred
        _pendingMppConsent.value = terms
        return try {
            deferred.await()
        } finally {
            pendingMppConsentContinuation = null
            _pendingMppConsent.value = null
        }
    }

    fun approveMppConsent(approval: ConsentApproval) {
        // Do not seed viewer balance from consent budget; source of truth is on-chain vault read.
        if (approval.approved) {
            _viewerVoucherUsageMicroUsdc.value = 0L
            _viewerSessionVaultMicroUsdc.value = 0L
            _viewerProgressBalanceMicroUsdc.value = 0L
        }
        pendingMppConsentContinuation?.complete(approval)
    }

    fun setViewerSessionVaultBalance(
        balanceMicroUsdc: Long,
        resetVoucherUsage: Boolean = false,
    ) {
        val normalized = balanceMicroUsdc.coerceAtLeast(0L)
        if (resetVoucherUsage) {
            _viewerVoucherUsageMicroUsdc.value = 0L
        }
        val usage = _viewerVoucherUsageMicroUsdc.value
        _viewerSessionVaultMicroUsdc.value = normalized
        _viewerProgressBalanceMicroUsdc.value = (normalized - usage).coerceAtLeast(0L)
    }

    fun rejectMppConsent() {
        pendingMppConsentContinuation?.complete(
            ConsentApproval(
                approved = false,
                autoPaySegments = false,
            ),
        )
    }

    fun applyViewerSegmentDebit(amountMicroUsdc: Long) {
        if (amountMicroUsdc <= 0L) return
        val onChain = _viewerSessionVaultMicroUsdc.value
        val usageBefore = _viewerVoucherUsageMicroUsdc.value
        val usageAfter = usageBefore + amountMicroUsdc
        val progressAfter = (onChain - usageAfter).coerceAtLeast(0L)

        _viewerVoucherUsageMicroUsdc.value = usageAfter
        _viewerProgressBalanceMicroUsdc.value = progressAfter

        Log.d(
            TAG,
            "💳 Viewer segment receipt/debit: -${amountMicroUsdc}µUSDC, onChain=${onChain / 1_000_000.0}, usage=${usageAfter / 1_000_000.0}, progress=${progressAfter / 1_000_000.0}",
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
                    .openSessionAndDeposit(
                        signer = signer,
                        viewerAddress = viewerAddress,
                        creatorAddress = creatorAddress,
                        depositAmountMicroUsdc = depositMicroUsdc,
                    ).getOrThrow()
            Log.e(
                TAG,
                "[VIEWER_SESSION_VAULT_TOPUP_OK] viewer=$viewerAddress creator=$creatorAddress amountMicroUsdc=$depositMicroUsdc txId=$txId",
            )

            val onChainRemaining =
                MppPayments.getRemainingBalanceFromSessionVault(
                    viewerAddress = viewerAddress,
                    hostAddress = creatorAddress,
                    appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                )

            if (onChainRemaining != null) {
                setViewerSessionVaultBalance(onChainRemaining, resetVoucherUsage = true)
            } else {
                Log.e(
                    TAG,
                    "[VIEWER_SESSION_VAULT_TOPUP_FETCH_NULL] viewer=$viewerAddress txId=$txId",
                )
            }

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

        return object : MppWalletSigner {
            override val address: String = address

            override suspend fun signTransaction(txn: Transaction): ByteArray =
                when (localAccount) {
                    is LocalAccount.Algo25 -> {
                        val secretKey =
                            getAlgo25SecretKey(address)
                                ?: error("Missing Algo25 key for $address")
                        val txnBytes =
                            com.algorand.algosdk.util.Encoder
                                .encodeToMsgPack(txn)
                        val signature =
                            signAlgo25ArbitraryData(txn.bytesToSign(), secretKey)
                                ?: error("Algo25 arbitrary signing failed")
                        Sdk.attachSignature(signature, txnBytes)
                    }

                    is LocalAccount.HdKey -> {
                        val seed =
                            getSeed(localAccount.seedId)
                                ?: error("Missing HD seed for $address")
                        signHdKeyTransaction(
                            transactionByteArray =
                                com.algorand.algosdk.util.Encoder.encodeToMsgPack(
                                    txn,
                                ),
                            seed = seed,
                            account = localAccount.account,
                            change = localAccount.change,
                            key = localAccount.keyIndex,
                        ) ?: error("HD signing failed")
                    }

                    is LocalAccount.Falcon24 -> {
                        val secretKey =
                            getFalcon24SecretKey(address)
                                ?: error("Missing Falcon24 key for $address")
                        signFalcon24Transaction(
                            transactionByteArray =
                                com.algorand.algosdk.util.Encoder.encodeToMsgPack(
                                    txn,
                                ),
                            publicKey = localAccount.publicKey,
                            privateKey = secretKey,
                        ) ?: error("Falcon24 signing failed")
                    }

                    else -> error("Unsupported account for MPP wallet signing: ${localAccount::class.simpleName}")
                }
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
                if (accountAddress != null && getAttestationApiResponse() != null) {
                    viewModelScope.launch {
                        saveCredential(
                            account = accountAddress,
                            credential = result.credential,
                            response = getAttestationApiResponse()!!,
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
