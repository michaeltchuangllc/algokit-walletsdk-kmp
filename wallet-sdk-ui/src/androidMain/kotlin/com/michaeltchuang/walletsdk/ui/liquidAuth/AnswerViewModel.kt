package com.michaeltchuang.walletsdk.ui.liquidAuth

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat.Builder
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.algorand.algosdk.transaction.Transaction
import com.algorand.algosdk.util.Encoder
import com.fasterxml.uuid.Generators
import com.google.android.gms.fido.fido2.api.common.PublicKeyCredential
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
import com.michaeltchuang.walletsdk.core.foundation.utils.Result
import com.michaeltchuang.walletsdk.core.foundation.utils.date.TimeProvider
import com.michaeltchuang.walletsdk.core.foundation.utils.toSuggestedParams
import com.michaeltchuang.walletsdk.core.liquidAuth.auth.connect.AuthMessage
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.usecases.LogAppSignatureUseCase
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.usecases.ManageSignalServiceUseCase
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.usecases.ProcessSignTransactionsUseCase
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.usecases.ProvideHttpClientUseCase
import com.michaeltchuang.walletsdk.core.passkeys.domain.model.PublicKeyCredentialCreationOptions
import com.michaeltchuang.walletsdk.core.passkeys.domain.repository.PasskeyRepository
import com.michaeltchuang.walletsdk.core.passkeys.domain.usecase.AddNewPasskey
import com.michaeltchuang.walletsdk.core.passkeys.domain.usecase.SetPasskeyLastUsedTime
import com.michaeltchuang.walletsdk.core.transaction.domain.usecase.GetTransactionParams
import com.michaeltchuang.walletsdk.ui.R
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases.AssertionIntentLauncherUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases.AttestationIntentLauncherUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases.HandleAssertionResultUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases.HandleAttestationResultUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases.PrepareAuthenticationUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases.ProcessBiometricTransactionSigningUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases.RegisterPasskeyUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.model.X402PaymentMessages
import com.michaeltchuang.walletsdk.ui.liquidAuth.payments.AlgorandX402Payments
import foundation.algorand.crypto.EncoderType
import foundation.algorand.provider.Message
import foundation.algorand.provider.avm.models.RequestMessage
import foundation.algorand.provider.avm.models.ResponseMessage
import foundation.algorand.provider.avm.models.SignTransactionsParams
import foundation.algorand.provider.avm.models.SignTransactionsResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.sol4k.Connection
import org.sol4k.PublicKey
import org.sol4k.instruction.TransferInstruction
import java.math.BigInteger
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.sol4k.Transaction as SolanaTransaction

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
    private val getTransactionParams: GetTransactionParams,
) : ViewModel(),
    EventViewModel<AnswerViewModel.ViewEvent> by eventDelegate {
    companion object {
        private const val TAG = "AnswerViewModel"
        const val NOTIFICATION_CHANNEL_ID = "NOTIFICATION_CHANNEL"
        const val SERVICE_NOTIFICATION_ID = 1000
        private const val STREAM_TIMEOUT_MS = 2000L // 2 seconds without frames = stream ended
        private const val BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        private const val LAMPORTS_PER_SOL = 1_000_000_000L
        private const val SOLANA_MAINNET_RPC = "https://api.mainnet-beta.solana.com"
        private const val SOLANA_DEVNET_RPC = "https://api.devnet.solana.com"
        private const val SOLANA_TESTNET_RPC = "https://api.testnet.solana.com"

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
    private val _session = MutableStateFlow("Logged Out")
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
        foundation.algorand.crypto.avm
            .Encoder()
    var currentChallenge: ByteArray? = null
    var showConfirmationDialog = MutableStateFlow(false)
    private var signalServiceConnection: android.content.ServiceConnection? = null
    private val _signalService =
        MutableStateFlow<com.michaeltchuang.walletsdk.core.liquidAuth.auth.connect.SignalService?>(
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
                kotlinx.coroutines.delay(500) // Check every 500ms
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
            _lastFrameTimestamp.value = System.currentTimeMillis()
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
        Log.d(TAG, "✅ Credential saved to local storage")
        eventDelegate.sendEvent(ViewEvent.ShowToast("✅ Credential saved to local storage"))
    }

    suspend fun getCredentialIdByAccountAddress(accountAddress: String): String? =
        passkeyRepository.getCredentialIdByAddress(accountAddress)

    suspend fun deleteCredentialByAccountAddress(accountAddress: String) {
        val credentialId = passkeyRepository.getCredentialIdByAddress(accountAddress)
        if (credentialId != null) {
            Log.d(TAG, "Deleting credential: $credentialId for address: $accountAddress")
            passkeyRepository.removePasskeyByCredentialId(credentialId)
        } else {
            Log.w(TAG, "No credential found to delete for address: $accountAddress")
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
                    Log.e(
                        TAG,
                        "Invalid SeedVault public key format for address=${localAccount.address}, decodedLength=${decoded?.size}",
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
        Encoder.decodeFromMsgPack(Base64.decode(unsignedTxn), Transaction::class.java)

    @OptIn(ExperimentalEncodingApi::class)
    fun handleMessages(
        msgStr: String,
        onSignTransaction: (SignTransactionsParams, Message) -> Unit,
        onVideoFrame: ((VideoFrameData) -> Unit)? = null,
    ) {
        try {
            Log.d(TAG, "========================================")
            Log.d(TAG, "📨 RECEIVED MESSAGE FROM DATACHANNEL")
            Log.d(TAG, "Message length: ${msgStr.length}")
            Log.d(TAG, "========================================")

            // Check if it's a video frame message (not Base64/CBOR encoded)
            if (msgStr.startsWith("{\"reference\":\"liquid:video:frame\"")) {
                Log.d(TAG, "🎥 Video frame message detected")
                handleVideoFrameMessage(msgStr, onVideoFrame)
                return
            }

            // Check for X402 payment messages (look for unique fields: amountMicroAlgos + creatorAddress)
            if (msgStr.contains("\"amountMicroAlgos\"") && msgStr.contains("\"creatorAddress\"")) {
                Log.d(TAG, "💰 X402 payment message detected: ${msgStr.take(100)}...")
                handleX402PaymentMessage(msgStr)
                return
            }

            val cborBytes = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(msgStr)

            // Log first bytes to verify incoming CBOR encoding type
            if (cborBytes.isNotEmpty()) {
                val firstBytes = cborBytes.take(10).joinToString(" ") { "0x%02X".format(it) }
                Log.d(TAG, "Incoming CBOR first bytes: $firstBytes")
                Log.d(
                    TAG,
                    "Incoming CBOR encoding: ${if (cborBytes[0].toInt() and 0x1F == 0x1F) "INDEFINITE-LENGTH" else "DEFINITE-LENGTH"}",
                )
            }

            val message = Message(cborBytes, EncoderType.CBOR)
            val request = encoder.decode<RequestMessage>(message.data, message.encoding)
            Log.d(TAG, "Message decoded - Reference: ${request.reference}")
            Log.d(TAG, "Request ID: ${request.id}")

            when (request.reference) {
                "arc0027:sign_transactions:request" -> {
                    Log.d(TAG, "✅ Transaction signing request detected")
                    viewModelScope.launch {
                        val params =
                            encoder.decode<SignTransactionsParams>(
                                encoder.encode(
                                    request.params,
                                    EncoderType.NONE,
                                ),
                                EncoderType.NONE,
                            )
                        Log.d(TAG, "Decoded ${params.txns.size} transaction(s) from request")
                        Log.d(TAG, "Provider ID: ${params.providerId}")
                        onSignTransaction(params, message)
                    }
                }
                "liquid:video:frame" -> {
                    Log.d(TAG, "🎥 Video frame message detected (CBOR encoded)")
                    // Handle CBOR-encoded video frame if needed
                }
                else -> {
                    Log.w(TAG, "⚠️ Unknown request reference: ${request.reference}")
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "❌ Error handling message: $e")
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
            val json = org.json.JSONObject(msgStr)
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

            Log.d(TAG, "🎥 Video frame decoded: ${videoFrame.width}x${videoFrame.height}, ${frameData.size} bytes")
            onVideoFrame?.invoke(videoFrame)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to decode video frame: $e")
        }
    }

    /**
     * Handle X402 payment messages from broadcaster
     */
    private fun handleX402PaymentMessage(msgStr: String) {
        Log.d(TAG, "💰 handleX402PaymentMessage called with: ${msgStr.take(200)}...")
        try {
            when {
                // Payment request: has amountMicroAlgos and creatorAddress
                msgStr.contains("\"amountMicroAlgos\"") && msgStr.contains("\"creatorAddress\"") && !msgStr.contains("\"status\"") -> {
                    Log.d(TAG, "💰 Parsing PaymentRequest...")
                    val request = X402PaymentMessages.PaymentRequest.fromJson(msgStr)
                    Log.d(
                        TAG,
                        "💰 Payment request parsed: id=${request.id}, amount=${request.amountMicroAlgos}, creator=${request.creatorAddress}",
                    )

                    // Store payment request for UI handling
                    _pendingPaymentRequest.value = request
                    Log.d(TAG, "💰 Stored in _pendingPaymentRequest")

                    // Emit event to show payment dialog
                    viewModelScope.launch {
                        Log.d(TAG, "💰 Sending PaymentRequested event to UI...")
                        eventDelegate.sendEvent(ViewEvent.PaymentRequested(request))
                        Log.d(TAG, "💰 PaymentRequested event sent!")
                    }
                }
                // Payment response: has status field
                msgStr.contains("\"status\"") -> {
                    Log.d(TAG, "💰 Parsing PaymentResponse...")
                    val response = X402PaymentMessages.PaymentResponse.fromJson(msgStr)
                    Log.d(TAG, "💰 Payment response: ${response.status}")
                    // Handle payment response...
                }
                // Balance update: has remainingBlocks
                msgStr.contains("\"remainingBlocks\"") || msgStr.contains("\"remainingMicroAlgos\"") -> {
                    Log.d(TAG, "💰 Parsing BalanceUpdate...")
                    val update = X402PaymentMessages.BalanceUpdate.fromJson(msgStr)
                    Log.d(TAG, "💰 Balance update: ${update.remainingAlgos()} ALGO remaining")
                    viewModelScope.launch {
                        eventDelegate.sendEvent(ViewEvent.BalanceUpdated(update))
                    }
                }
                // Funds depleted: has totalBlocksWatched
                msgStr.contains("\"totalBlocksWatched\"") -> {
                    Log.d(TAG, "💰 Parsing FundsDepleted...")
                    val depleted = X402PaymentMessages.FundsDepleted.fromJson(msgStr)
                    Log.d(TAG, "💰 Funds depleted after ${depleted.totalBlocksWatched} blocks")
                    viewModelScope.launch {
                        eventDelegate.sendEvent(ViewEvent.FundsDepleted(depleted))
                    }
                }
                else -> {
                    Log.w(TAG, "💰 Unknown X402 message type: ${msgStr.take(100)}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to handle X402 payment message: $e")
        }
    }

    /**
     * Create and send X402 payment response with pre-signed transaction
     */
    fun sendPaymentResponse(
        request: X402PaymentMessages.PaymentRequest,
        status: X402PaymentMessages.PaymentResponse.Status,
        signedTransactionB64: String? = null,
        errorMessage: String? = null,
    ) {
        val response =
            X402PaymentMessages.PaymentResponse(
                id = request.id,
                signedTransactionB64 = signedTransactionB64 ?: "",
                clientAddress = _accountAddress.value,
                status = status,
                errorMessage = errorMessage,
            )

        // Send via data channel
        val signalService = _signalService.value
        if (signalService != null) {
            val msgJson = response.toJson()
            signalService.send(msgJson)
            Log.d(TAG, "💰 Payment response sent: ${status.name}")
        } else {
            Log.e(TAG, "💰 Cannot send payment response - SignalService not available")
        }
    }

    /**
     * Create and send X402 payment transaction for a payment request.
     * Uses Solana transfer flow for Solana/SeedVault accounts and existing Algorand flow otherwise.
     */
    suspend fun createAndSendPayment(request: X402PaymentMessages.PaymentRequest) {
        val accountAddress = _accountAddress.value
        if (accountAddress.isEmpty()) {
            Log.e(TAG, "💰 Cannot create payment - no account address")
            sendPaymentResponse(
                request = request,
                status = X402PaymentMessages.PaymentResponse.Status.ERROR,
                errorMessage = "No account connected",
            )
            return
        }

        val localAccount = getLocalAccount(accountAddress)
        if (localAccount == null) {
            Log.e(TAG, "💰 No local account found for address: $accountAddress")
            sendPaymentResponse(
                request = request,
                status = X402PaymentMessages.PaymentResponse.Status.ERROR,
                errorMessage = "Account not found in wallet",
            )
            return
        }

        val shouldUseSolanaFlow =
            localAccount is LocalAccount.SeedVault ||
                (decodeBase58(accountAddress)?.size == 32 && decodeBase58(request.creatorAddress)?.size == 32)

        if (shouldUseSolanaFlow) {
            createAndSendSolanaPayment(request, accountAddress)
            return
        }

        // Existing Algorand flow
        val txnParams = getTransactionParams()
        val transactionParams =
            when (txnParams) {
                is Result.Success -> txnParams.data
                is Result.Error -> {
                    Log.e(TAG, "💰 Failed to get transaction params")
                    sendPaymentResponse(
                        request = request,
                        status = X402PaymentMessages.PaymentResponse.Status.ERROR,
                        errorMessage = "Failed to get network params",
                    )
                    return
                }
            }

        val unsignedTxn =
            AlgorandX402Payments.createDepositTransaction(
                senderAddress = accountAddress,
                creatorAddress = request.creatorAddress,
                sessionId = request.id,
                suggestedParams = transactionParams.toSuggestedParams(),
            )

        val signedTxnBytes =
            when (localAccount) {
                is LocalAccount.Algo25 -> {
                    val secretKey = getAlgo25SecretKey(accountAddress)
                    if (secretKey == null) {
                        Log.e(TAG, "💰 Failed to get Algo25 secret key")
                        sendPaymentResponse(
                            request = request,
                            status = X402PaymentMessages.PaymentResponse.Status.ERROR,
                            errorMessage = "Failed to access account key",
                        )
                        return
                    }
                    AlgorandX402Payments.signTransaction(unsignedTxn, secretKey)
                }
                is LocalAccount.HdKey -> {
                    val seed = getSeed(localAccount.seedId)
                    if (seed == null) {
                        Log.e(TAG, "💰 Failed to get HD seed")
                        sendPaymentResponse(
                            request = request,
                            status = X402PaymentMessages.PaymentResponse.Status.ERROR,
                            errorMessage = "Failed to access HD seed",
                        )
                        return
                    }
                    signHdKeyTransaction(
                        unsignedTxn,
                        seed,
                        localAccount.account,
                        localAccount.change,
                        localAccount.keyIndex,
                    ) ?: run {
                        Log.e(TAG, "💰 HD Key transaction signing returned null")
                        sendPaymentResponse(
                            request = request,
                            status = X402PaymentMessages.PaymentResponse.Status.ERROR,
                            errorMessage = "HD transaction signing failed",
                        )
                        return
                    }
                }
                is LocalAccount.Falcon24 -> {
                    val secretKey = getFalcon24SecretKey(localAccount.address)
                    if (secretKey == null) {
                        Log.e(TAG, "💰 Failed to get Falcon24 secret key")
                        sendPaymentResponse(
                            request = request,
                            status = X402PaymentMessages.PaymentResponse.Status.ERROR,
                            errorMessage = "Failed to access Falcon24 key",
                        )
                        return
                    }
                    signFalcon24Transaction(
                        unsignedTxn,
                        localAccount.publicKey,
                        secretKey,
                    ) ?: run {
                        Log.e(TAG, "💰 Falcon24 transaction signing returned null")
                        sendPaymentResponse(
                            request = request,
                            status = X402PaymentMessages.PaymentResponse.Status.ERROR,
                            errorMessage = "Falcon24 transaction signing failed",
                        )
                        return
                    }
                }
                else -> {
                    Log.e(TAG, "💰 Unsupported account type: ${localAccount::class.simpleName}")
                    sendPaymentResponse(
                        request = request,
                        status = X402PaymentMessages.PaymentResponse.Status.ERROR,
                        errorMessage = "Unsupported account type",
                    )
                    return
                }
            }

        if (signedTxnBytes == null) {
            Log.e(TAG, "💰 Transaction signing failed")
            sendPaymentResponse(
                request = request,
                status = X402PaymentMessages.PaymentResponse.Status.ERROR,
                errorMessage = "Transaction signing failed",
            )
            return
        }

        val signedB64 = Encoder.encodeToBase64(signedTxnBytes)
        sendPaymentResponse(
            request = request,
            status = X402PaymentMessages.PaymentResponse.Status.SIGNED,
            signedTransactionB64 = signedB64,
        )
        Log.d(TAG, "💰 Payment sent successfully for session ${request.id}")
    }

    private suspend fun createAndSendSolanaPayment(
        request: X402PaymentMessages.PaymentRequest,
        accountAddress: String,
    ) {
        val localAccount = getLocalAccount(accountAddress)
        if (localAccount !is LocalAccount.SeedVault) {
            sendPaymentResponse(
                request = request,
                status = X402PaymentMessages.PaymentResponse.Status.ERROR,
                errorMessage = "Solana payments require a Seed Vault account",
            )
            return
        }

        val amountSol = request.amountMicroAlgos / 1_000_000.0
        val txData =
            createSolanaTransferTransactionData(
                fromPublicKey = localAccount.publicKey,
                toPublicKey = request.creatorAddress,
                amountSol = amountSol,
                network = request.network,
            )

        if (txData == null) {
            sendPaymentResponse(
                request = request,
                status = X402PaymentMessages.PaymentResponse.Status.ERROR,
                errorMessage = "Failed to create Solana transfer transaction",
            )
            return
        }

        val signerDerivationPath = "m/44'/${localAccount.chainId}'/0'"

        viewModelScope.launch {
            eventDelegate.sendEvent(
                ViewEvent.SignSolanaX402Payment(
                    paymentRequest = request,
                    serializedMessage = txData.serializedMessage,
                    signerAddress = accountAddress,
                    signerPublicKey = localAccount.publicKey,
                    signerDerivationPath = signerDerivationPath,
                ),
            )
        }
    }

    private data class SolanaTransferTxData(
        val serializedMessage: ByteArray,
    )

    private suspend fun createSolanaTransferTransactionData(
        fromPublicKey: String,
        toPublicKey: String,
        amountSol: Double,
        network: String,
    ): SolanaTransferTxData? =
        try {
            val rpcEndpoint = SOLANA_DEVNET_RPC

            val connection = Connection(rpcEndpoint)
            val lamports = (amountSol * LAMPORTS_PER_SOL).toLong()
            val fromPubKey = PublicKey(fromPublicKey)
            val toPubKey = PublicKey(toPublicKey)
            val recentBlockhash = connection.getLatestBlockhash()
            val transferInstruction = TransferInstruction(fromPubKey, toPubKey, lamports)
            val transaction = SolanaTransaction(recentBlockhash, transferInstruction, fromPubKey)
            val serializedWithEmptySig = transaction.serialize()
            val serializedMessage =
                if (serializedWithEmptySig.isNotEmpty() && serializedWithEmptySig[0] == 0.toByte()) {
                    serializedWithEmptySig.copyOfRange(1, serializedWithEmptySig.size)
                } else {
                    serializedWithEmptySig
                }

            SolanaTransferTxData(serializedMessage = serializedMessage)
        } catch (e: Exception) {
            Log.e(TAG, "💰 Failed to create Solana transfer transaction", e)
            null
        }

    // Pending payment request for UI
    private val _pendingPaymentRequest = MutableStateFlow<X402PaymentMessages.PaymentRequest?>(null)
    val pendingPaymentRequest: StateFlow<X402PaymentMessages.PaymentRequest?> = _pendingPaymentRequest

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
                val result = kotlinx.coroutines.runBlocking { processSignTransactions(params) }
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

    suspend fun processBiometricTransactionSigning(
        activity: androidx.fragment.app.FragmentActivity,
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
    ): Builder =
        Builder(context, channelId)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setColor(ContextCompat.getColor(context, androidx.biometric.R.color.biometric_error_color))
            .setSmallIcon(R.drawable.ic_key)

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
            val publicKeyCredentialRequestOptions: com.google.android.gms.fido.fido2.api.common.PublicKeyCredentialRequestOptions,
            val credentialId: String,
        ) : ViewEvent

        data class RegistrationSuccess(
            val pubKeyCredentialCreationOptions: com.google.android.gms.fido.fido2.api.common.PublicKeyCredentialCreationOptions,
            val accountAddress: String,
        ) : ViewEvent

        data class VideoFrameReceived(
            val frame: VideoFrameData,
        ) : ViewEvent

        // ================= X402 Payment Events =================

        data class PaymentRequested(
            val paymentRequest: X402PaymentMessages.PaymentRequest,
        ) : ViewEvent

        data class BalanceUpdated(
            val balanceUpdate: X402PaymentMessages.BalanceUpdate,
        ) : ViewEvent

        data class FundsDepleted(
            val depleted: X402PaymentMessages.FundsDepleted,
        ) : ViewEvent

        data class SignSolanaX402Payment(
            val paymentRequest: X402PaymentMessages.PaymentRequest,
            val serializedMessage: ByteArray,
            val signerAddress: String,
            val signerPublicKey: String,
            val signerDerivationPath: String,
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
