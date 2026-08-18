package com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.biometric.R
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewModelScope
import com.algorand.algosdk.transaction.Transaction
import com.google.android.gms.fido.fido2.api.common.PublicKeyCredential
import com.google.android.gms.fido.fido2.api.common.PublicKeyCredentialRequestOptions
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAccountAlgoBalance
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAlgo25SecretKey
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetFalcon24SecretKey
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetFalcon25PrivateKey
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetHdSeed
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetLocalAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetLocalAccounts
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.network.domain.usecase.GetCurrentNetworkUseCase
import com.michaeltchuang.walletsdk.core.network.usecase.GetCurrentBlockUseCase
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ChatMessage
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetRemainingSessionVaultBalanceUseCase
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetSessionVaultConfigUseCase
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.MppWalletSignerUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases.HandleAssertionResultUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases.HandleAttestationResultUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases.PrepareAuthenticationUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases.RegisterPasskeyUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.service.LiquidAuthPlatformServices
import com.michaeltchuang.walletsdk.ui.liquidStream.domain.manager.MppPaymentViewerManager
import com.michaeltchuang.walletsdk.ui.liquidStream.domain.usecases.SetupMppPaymentViewerUseCase
import foundation.algorand.crypto.EncoderType
import foundation.algorand.crypto.avm.Encoder
import foundation.algorand.provider.Message
import foundation.algorand.provider.avm.models.RequestMessage
import foundation.algorand.provider.avm.models.ResponseMessage
import foundation.algorand.provider.avm.models.SignTransactionsParams
import foundation.algorand.provider.avm.models.SignTransactionsResult
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONObject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64

actual open class AnswerViewModel actual constructor(
    getCurrentBlockUseCase: GetCurrentBlockUseCase,
    getAccountAlgoBalance: GetAccountAlgoBalance,
    getLocalAccount: GetLocalAccount,
    getLocalAccounts: GetLocalAccounts,
    getAlgo25SecretKey: GetAlgo25SecretKey,
    getFalcon24SecretKey: GetFalcon24SecretKey,
    getFalcon25PrivateKey: GetFalcon25PrivateKey,
    getSeed: GetHdSeed,
    getCurrentNetworkUseCase: GetCurrentNetworkUseCase,
    getRemainingSessionVaultBalanceUseCase: GetRemainingSessionVaultBalanceUseCase,
    getSessionVaultConfigUseCase: GetSessionVaultConfigUseCase,
    setupMppPaymentViewerUseCase: SetupMppPaymentViewerUseCase,
    mppPaymentViewerManager: MppPaymentViewerManager,
    mppWalletSignerUseCase: MppWalletSignerUseCase,
) : CommonAnswerViewModel(
        getCurrentBlockUseCase = getCurrentBlockUseCase,
        getAccountAlgoBalance = getAccountAlgoBalance,
        getLocalAccount = getLocalAccount,
        getLocalAccounts = getLocalAccounts,
        getAlgo25SecretKey = getAlgo25SecretKey,
        getFalcon24SecretKey = getFalcon24SecretKey,
        getFalcon25PrivateKey = getFalcon25PrivateKey,
        getSeed = getSeed,
        getCurrentNetworkUseCase = getCurrentNetworkUseCase,
        getRemainingSessionVaultBalanceUseCase = getRemainingSessionVaultBalanceUseCase,
        getSessionVaultConfigUseCase = getSessionVaultConfigUseCase,
        setupMppPaymentViewerUseCase = setupMppPaymentViewerUseCase,
        mppPaymentViewerManager = mppPaymentViewerManager,
        mppWalletSignerUseCase = mppWalletSignerUseCase,
    ),
    EventViewModel<AnswerViewModel.ViewEvent> {
    companion object {
        private const val TAG = "AnswerViewModel"
        const val NOTIFICATION_CHANNEL_ID = "NOTIFICATION_CHANNEL"
        const val SERVICE_NOTIFICATION_ID = 1000
    }

    private lateinit var platformServices: LiquidAuthPlatformServices

    constructor(
        platformServices: LiquidAuthPlatformServices,
        getAlgo25SecretKey: GetAlgo25SecretKey,
        getFalcon24SecretKey: GetFalcon24SecretKey,
        getLocalAccount: GetLocalAccount,
        getLocalAccounts: GetLocalAccounts,
        getSeed: GetHdSeed,
        getAccountAlgoBalance: GetAccountAlgoBalance,
        getCurrentBlockUseCase: GetCurrentBlockUseCase,
        setupMppPaymentViewerUseCase: SetupMppPaymentViewerUseCase,
        mppPaymentViewerManager: MppPaymentViewerManager,
        getFalcon25PrivateKey: GetFalcon25PrivateKey,
        getCurrentNetworkUseCase: GetCurrentNetworkUseCase,
        getRemainingSessionVaultBalanceUseCase: GetRemainingSessionVaultBalanceUseCase,
        getSessionVaultConfigUseCase: GetSessionVaultConfigUseCase,
        mppWalletSignerUseCase: MppWalletSignerUseCase,
    ) : this(
        getCurrentBlockUseCase = getCurrentBlockUseCase,
        getAccountAlgoBalance = getAccountAlgoBalance,
        getLocalAccount = getLocalAccount,
        getLocalAccounts = getLocalAccounts,
        getAlgo25SecretKey = getAlgo25SecretKey,
        getFalcon24SecretKey = getFalcon24SecretKey,
        getFalcon25PrivateKey = getFalcon25PrivateKey,
        getSeed = getSeed,
        getCurrentNetworkUseCase = getCurrentNetworkUseCase,
        getRemainingSessionVaultBalanceUseCase = getRemainingSessionVaultBalanceUseCase,
        getSessionVaultConfigUseCase = getSessionVaultConfigUseCase,
        setupMppPaymentViewerUseCase = setupMppPaymentViewerUseCase,
        mppPaymentViewerManager = mppPaymentViewerManager,
        mppWalletSignerUseCase = mppWalletSignerUseCase,
    ) {
        this.platformServices = platformServices
    }

    // State
    override val viewEvent: Flow<ViewEvent> get() = platformServices.viewEvent

    val userAgent: String by lazy {
        val applicationId = "com.michaeltchuang.walletsdk.demo"
        val versionName = "1.0"
        "$applicationId/$versionName (Android ${Build.VERSION.RELEASE}; ${Build.MODEL}; ${Build.BRAND})"
    }

    private val providerId = "provider-${currentTimeMillis()}"
    private var currentAccountType: String = "algorand"
    private val encoder = Encoder()

    var currentChallenge: ByteArray? = null

    private val _pendingSignTransactionsParams = MutableStateFlow<SignTransactionsParams?>(null)
    val pendingSignTransactionsParams: StateFlow<SignTransactionsParams?> = _pendingSignTransactionsParams

    private val _pendingSignMessage = MutableStateFlow<Message?>(null)
    val pendingSignMessage: StateFlow<Message?> = _pendingSignMessage

    val signalService get() = platformServices.signalService

    init {
        viewModelScope.launch {
            pendingSignTransactionRequest
                .map { pendingRequest -> pendingRequest?.params as? SignTransactionsParams }
                .collect { _pendingSignTransactionsParams.value = it }
        }
        viewModelScope.launch {
            pendingSignTransactionRequest
                .map { pendingRequest -> pendingRequest?.message as? Message }
                .collect { _pendingSignMessage.value = it }
        }
    }

    private var attestationApiResponse: String? = null

    /**
     * Android-specific stream-timeout teardown. The shared base already clears the frame and
     * resets session state; here we stop the bound SignalService and notify the UI.
     */
    override fun onStreamTimeout(reason: String) {
        platformServices.onStreamTimeout(this, reason)
    }

    override fun doSendChatMessage(message: ChatMessage) {
        mppPaymentViewerManager.sendChatMessage(message)
    }

    override fun onChatMessageReceived(message: ChatMessage) {
        super.onChatMessageReceived(message)
    }

    // --- Public Setters and Helpers ---
    override fun setAccountAddress(address: String) {
        super.setAccountAddress(address)
        viewModelScope.launch {
            currentAccountType =
                when (getLocalAccount(address)) {
                    is LocalAccount.SeedVault -> "solana"
                    else -> "algorand"
                }
        }
    }

    fun getProvideHttpClient(): OkHttpClient = platformServices.getProvideHttpClient()

    fun getAttestationApiResponse(): String? = attestationApiResponse

    fun setAttestationApiResponse(value: String?) {
        attestationApiResponse = value
    }

    fun logAppSignature(context: Context) {
        platformServices.logAppSignature(context)
    }

    // --- Signal Service API ---
    fun bindSignalService(context: Context) {
        platformServices.bindSignalService(context)
    }

    fun unbindSignalService(context: Context) {
        platformServices.unbindSignalService(context, this)
    }

    // --- Credential Management ---
    suspend fun saveCredential(
        account: String,
        credential: PublicKeyCredential,
        response: String,
    ) {
        platformServices.saveCredential(account, credential, response)
    }

    suspend fun getCredentialIdByAccountAddress(accountAddress: String): String? =
        platformServices.getCredentialIdByAccountAddress(accountAddress)

    suspend fun deleteCredentialByAccountAddress(accountAddress: String) {
        platformServices.deleteCredentialByAccountAddress(accountAddress)
    }

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
    suspend fun getMnemonic(address: String): String? = platformServices.getMnemonic(address)

    // --- AVM & DataChannel Message Logic ---
    private fun decodeUnsignedTransaction(unsignedTxn: String): Transaction? =
        com.algorand.algosdk.util.Encoder
            .decodeFromMsgPack(Base64.Default.decode(unsignedTxn), Transaction::class.java)

    fun handleMessages(
        msgStr: String,
        onSignTransaction: ((SignTransactionsParams, Message) -> Unit)? = null,
        onVideoFrame: ((VideoFrameData) -> Unit)? = null,
    ) {
        handleDataChannelMessage(
            msgStr = msgStr,
            onSignTransaction = { pendingRequest ->
                val params = pendingRequest.params as? SignTransactionsParams
                val message = pendingRequest.message as? Message
                if (params != null && message != null) {
                    onSignTransaction?.invoke(params, message)
                }
            },
            onVideoFrame = onVideoFrame,
        )
    }

    fun encodeResponseMessage(responseMessage: ResponseMessage): ByteArray = encoder.encode(responseMessage, EncoderType.CBOR)

    override suspend fun handleCborRequestMessage(cborBytes: ByteArray): DataChannelRequest? {
        val message = Message(cborBytes, EncoderType.CBOR)
        val request = encoder.decode<RequestMessage>(message.data, message.encoding)
        Napier.d(tag = TAG, message = "Message decoded - Reference: ${request.reference}")
        Napier.d(tag = TAG, message = "Request ID: ${request.id}")

        return when (request.reference) {
            "arc0027:sign_transactions:request" -> {
                val params =
                    encoder.decode<SignTransactionsParams>(
                        encoder.encode(request.params, EncoderType.NONE),
                        EncoderType.NONE,
                    )
                Napier.d(tag = TAG, message = "Transaction signing request detected")
                Napier.d(tag = TAG, message = "Decoded ${params.txns.size} transaction(s)")
                DataChannelRequest.SignTransactions(PendingSignTransactionRequest(params, message))
            }

            "liquid:video:frame" -> {
                Napier.d(tag = TAG, message = "Video frame message (CBOR encoded)")
                DataChannelRequest.VideoFrame(Base64.Default.encode(cborBytes))
            }

            else -> {
                Napier.w(tag = TAG, message = "Unknown request reference: ${request.reference}")
                null
            }
        }
    }

    fun handleMessage(message: Message): Any {
        val decoded = encoder.decode<RequestMessage>(message.data, message.encoding)
        when (decoded.reference) {
            "arc0027:sign_transactions:request" -> {
                val params =
                    encoder.decode<SignTransactionsParams>(
                        encoder.encode(decoded.params, EncoderType.NONE),
                        EncoderType.NONE,
                    )
                val result = runBlocking { processSignTransactions(params) }
                return ResponseMessage(
                    id = "response-${currentTimeMillis()}",
                    reference = "arc0027:sign_transactions:response",
                    requestId = decoded.id,
                    result = result,
                )
            }

            else -> throw IllegalArgumentException("Invalid reference: ${decoded.reference}")
        }
    }

    suspend fun processSignTransactions(params: SignTransactionsParams): SignTransactionsResult =
        platformServices.processSignTransactionsUseCase(
            params = params,
            providerId = providerId,
            accountAddress = accountAddress.value,
        )

    fun setupMppPaymentViewer(viewerAddress: String?) {
        val address = viewerAddress?.takeIf { it.isNotBlank() } ?: return
        val service = signalService.value
        if (service?.peerConnection == null) {
            Log.e(TAG, "[VIEWER_MPP_SETUP_SKIP] reason=missing_peer_connection")
            return
        }
        viewModelScope.launch {
            try {
                // Unlike iOS — where Swift hands Kotlin an already-ready data channel — Android's
                // payment channel is created by the remote host peer, so we must wait for it
                // (and resolve the signer/network) before the shared use case can run.
                val paymentChannel =
                    platformServices.awaitViewerPaymentDataChannel(service)
                        ?: run {
                            Log.e(TAG, "[VIEWER_MPP_SETUP_SKIP] reason=missing_payment_channel viewer=$address")
                            return@launch
                        }
                val signer =
                    buildMppWalletSigner(address)
                        ?: run {
                            Log.e(TAG, "[VIEWER_MPP_SETUP_SKIP] reason=missing_signer viewer=$address")
                            return@launch
                        }
                val mppNetwork = resolveMppClientNetwork(address)

                setupMppPaymentViewerUseCase(
                    SetupMppPaymentViewerUseCase.Params(
                        dataChannel = platformServices.wrapPaymentDataChannel(paymentChannel),
                        viewerAddress = address,
                        scope = viewModelScope,
                        signer = signer,
                        mppNetwork = mppNetwork,
                        requestMppConsent = ::requestMppConsentFromUi,
                        setViewerSessionVaultProgress = ::setViewerSessionVaultProgress,
                        signFido2Challenge = ::signFido2Challenge,
                        onChatMessageReceived = ::onChatMessageReceived,
                    ),
                )
            } catch (_: CancellationException) {
                Log.w(TAG, "[VIEWER_MPP_SETUP_CANCELLED] viewer=$address")
            } catch (e: Exception) {
                Log.e(TAG, "[VIEWER_MPP_SETUP_FAILED] viewer=$address", e)
            }
        }
    }

    suspend fun processBiometricTransactionSigning(
        activity: FragmentActivity,
        params: SignTransactionsParams,
        message: Message,
    ) {
        platformServices.processBiometricTransactionSigning(this, activity, params, message)
    }

    suspend fun preparePasskeyRegistration(
        authMessage: AuthMessage,
        accountAddress: String,
        options: JSONObject = JSONObject(),
        onSessionUpdate: (String?) -> Unit = {},
    ): RegisterPasskeyUseCase.Result =
        platformServices.preparePasskeyRegistration(
            viewModel = this,
            authMessage = authMessage,
            accountAddress = accountAddress,
            options = options,
            onSessionUpdate = onSessionUpdate,
        )

    fun registerPasskey(
        authMessage: AuthMessage,
        accountAddress: String,
        options: JSONObject = JSONObject(),
    ) {
        platformServices.registerPasskey(this, authMessage, accountAddress, options)
    }

    suspend fun prepareAuthentication(
        authMessage: AuthMessage,
        credentialId: String,
        onSessionUpdate: (String?) -> Unit = {},
        onCredentialNotFound: () -> Unit = {},
    ): PrepareAuthenticationUseCase.Result =
        platformServices.prepareAuthentication(
            viewModel = this,
            authMessage = authMessage,
            credentialId = credentialId,
            onSessionUpdate = onSessionUpdate,
            onCredentialNotFound = onCredentialNotFound,
        )

    fun authenticate(
        authMessage: AuthMessage,
        credentialId: String,
        setSession: ((String?) -> Unit)? = null,
        onCredentialNotFound: (() -> Unit)? = null,
    ) {
        platformServices.authenticate(this, authMessage, credentialId, setSession, onCredentialNotFound)
    }

    fun handleAssertionResultFromLauncher(result: HandleAssertionResultUseCase.Result) {
        platformServices.handleAssertionResultFromLauncher(this, result)
    }

    fun handleAttestationResultFromLauncher(
        result: HandleAttestationResultUseCase.Result,
        accountAddress: String?,
    ) {
        platformServices.handleAttestationResultFromLauncher(this, result, accountAddress)
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
            .setSmallIcon(android.R.drawable.ic_dialog_info)

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

        data class StreamDisconnected(
            val reason: String,
        ) : ViewEvent
    }
}
