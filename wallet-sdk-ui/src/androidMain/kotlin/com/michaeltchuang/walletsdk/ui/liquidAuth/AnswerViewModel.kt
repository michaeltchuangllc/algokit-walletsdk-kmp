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
import com.algorand.algosdk.account.Account
import com.algorand.algosdk.transaction.Transaction
import com.algorand.algosdk.util.Encoder
import com.fasterxml.uuid.Generators
import com.google.android.gms.fido.fido2.api.common.PublicKeyCredential
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAccountMnemonic
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetFalcon24SecretKey
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetHdSeed
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetLocalAccount
import com.michaeltchuang.walletsdk.core.algosdk.signFalcon24ArbitraryData
import com.michaeltchuang.walletsdk.core.algosdk.signHdKeyData
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.foundation.utils.date.TimeProvider
import com.michaeltchuang.walletsdk.core.liquidAuth.auth.connect.AuthMessage
import com.michaeltchuang.walletsdk.core.passkeys.domain.model.PublicKeyCredentialCreationOptions
import com.michaeltchuang.walletsdk.core.passkeys.domain.repository.PasskeyRepository
import com.michaeltchuang.walletsdk.core.passkeys.domain.usecase.AddNewPasskey
import com.michaeltchuang.walletsdk.core.passkeys.domain.usecase.SetPasskeyLastUsedTime
import com.michaeltchuang.walletsdk.ui.R
import com.michaeltchuang.walletsdk.ui.liquidAuth.usecases.AssertionApiUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.usecases.AssertionIntentLauncherUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.usecases.AttestationApiUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.usecases.AttestationIntentLauncherUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.usecases.HandleAssertionResultUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.usecases.HandleAttestationResultUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.usecases.LogAppSignatureUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.usecases.ManageSignalServiceUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.usecases.PrepareAuthenticationUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.usecases.ProcessBiometricTransactionSigningUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.usecases.ProcessSignTransactionsUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.usecases.ProvideHttpClientUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.usecases.RegisterPasskeyUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.usecases.ShowTransactionConfirmationDialogUseCase
import foundation.algorand.crypto.EncoderType
import foundation.algorand.crypto.avm.KeyPairs
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
import okhttp3.Response
import org.json.JSONObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Demo View Model
 *
 * Minimal state to handle FIDO2 PublicKeyCredentials and Proof of Knowledge
 * Now includes AVMProvider logic directly
 */
class AnswerViewModel(
    private val addNewPasskey: AddNewPasskey,
    private val passkeyRepository: PasskeyRepository,
    private val setPasskeyLastUsedTime: SetPasskeyLastUsedTime,
    private val getAccountMnemonic: GetAccountMnemonic,
    private val timeProvider: TimeProvider,
    private val getFalcon24SecretKey: GetFalcon24SecretKey,
    private val getLocalAccount: GetLocalAccount,
    private val getSeed: GetHdSeed,
    private val showTransactionConfirmationDialogUseCase: ShowTransactionConfirmationDialogUseCase,
    private val processBiometricTransactionSigningUseCase: ProcessBiometricTransactionSigningUseCase,
    private val registerPasskeyUseCase: RegisterPasskeyUseCase,
    private val prepareAuthenticationUseCase: PrepareAuthenticationUseCase,
    private val manageSignalServiceUseCase: ManageSignalServiceUseCase,
    private val processSignTransactionsUseCase: ProcessSignTransactionsUseCase,
    private val attestationIntentLauncherUseCase: AttestationIntentLauncherUseCase,
    private val assertionIntentLauncherUseCase: AssertionIntentLauncherUseCase,
    private val eventDelegate: EventDelegate<ViewEvent>,
    private val logAppSignatureUseCase: LogAppSignatureUseCase,
    private val attestationApiUseCase: AttestationApiUseCase,
    private val assertionApiUseCase: AssertionApiUseCase,
    private val providerHttpClientUseCase: ProvideHttpClientUseCase
) : ViewModel(),
    EventViewModel<AnswerViewModel.ViewEvent> by eventDelegate {
    companion object {
        private const val TAG = "AnswerViewModel"
        const val NOTIFICATION_CHANNEL_ID = "NOTIFICATION_CHANNEL"
        const val SERVICE_NOTIFICATION_ID = 1000
    }
    override val viewEvent: Flow<ViewEvent> get() = eventDelegate.viewEvent

    // User Agent for API requests
    val userAgent: String by lazy {
        val applicationId = "com.michaeltchuang.walletsdk.demo"
        val versionName = "1.0"
        "$applicationId/$versionName (Android ${Build.VERSION.RELEASE}; ${Build.MODEL}; ${Build.BRAND})"
    }

    // ==================== StateFlow ====================
    private val _session = MutableStateFlow("Logged Out")
    val session: StateFlow<String> = _session

    fun setSession(cookie: String?) {
        if (cookie !== null) {
            _session.value = cookie
        }
    }

    private val _message = MutableStateFlow<AuthMessage?>(null)
    val message: StateFlow<AuthMessage?> = _message

    fun setMessage(msg: AuthMessage?) {
        _message.value = msg
    }

    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count

    fun setCount(i: Int) {
        _count.value = i
    }

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun setError(errorMessage: String?) {
        _error.value = errorMessage
    }

    fun clearError() {
        _error.value = null
    }

    // ==================== AVMProvider Logic ====================
    // Datachannel Provider/Handler
    private val uuidGenerator = Generators.timeBasedEpochRandomGenerator()

    // Must be unique to this provider
    private val providerId = uuidGenerator.generate().toString()

    // Account address for the provider - exposed as StateFlow for UI
    private val _accountAddress = MutableStateFlow("")
    val accountAddress: StateFlow<String> = _accountAddress

    // Encoder for message processing
    private val encoder =
        foundation.algorand.crypto.avm
            .Encoder()

    fun setAccountAddress(address: String) {
        _accountAddress.value = address
    }

    var currentChallenge: ByteArray? = null

    private var signalServiceConnection: android.content.ServiceConnection? = null
    private val _signalService =
        MutableStateFlow<com.michaeltchuang.walletsdk.core.liquidAuth.auth.connect.SignalService?>(
            null
        )
    val signalService = _signalService

    /**
     * Binds to SignalService using the given Context.
     *
     * IMPORTANT: For proper lifecycle safety, always pass an Activity context
     * (never Application context) from your UI layer!
     */
    fun bindSignalService(context: Context) {
        signalServiceConnection = manageSignalServiceUseCase(context) {
            _signalService.value = it
        }
    }

    /**
     * Unbinds from the SignalService using the given Context.
     *
     * IMPORTANT: Always use the same Activity context used for binding.
     */
    fun unbindSignalService(context: Context) {
        signalServiceConnection?.let { manageSignalServiceUseCase.unbind(context, it) }
        _signalService.value = null // Explicitly clear reference
    }

    // ==================== Credential Methods ====================
    suspend fun saveCredential(
        account: String,
        credential: PublicKeyCredential,
        response: String,
    ) {
        val requestOption = PublicKeyCredentialCreationOptions(response)
        addNewPasskey(
            algoAddress = account,
            requestOptions = requestOption,
            credId = credential.rawId!!,
        )
        Log.d(TAG, "✅ Credential saved to local storage")
        eventDelegate.sendEvent(ViewEvent.ShowToast("✅ Registration successful! Credential saved."))
    }

    suspend fun getCredentialIdByAlgoAddress(algoAddress: String): String? {
        val credentialId = passkeyRepository.getCredentialIdByAlgoAddress(algoAddress)
        return credentialId
    }

    suspend fun deleteCredentialByAlgoAddress(algoAddress: String) {
        val credentialId = passkeyRepository.getCredentialIdByAlgoAddress(algoAddress)
        if (credentialId != null) {
            Log.d(TAG, "Deleting credential: $credentialId for address: $algoAddress")
            passkeyRepository.removePasskeyByCredentialId(credentialId)
        } else {
            Log.w(TAG, "No credential found to delete for address: $algoAddress")
        }
    }

    fun getCredentialMessage(
        account: String,
        credential: PublicKeyCredential,
    ): JSONObject {
        val credMessage = JSONObject()
        credMessage.put("address", account)
        credMessage.put("device", Build.MODEL)
        credMessage.put("origin", message.value!!.origin)
        credMessage.put("id", credential.id)
        credMessage.put("prevCounter", count.value!!)
        credMessage.put("type", "credential")
        return credMessage
    }

    suspend fun getMnemonic(address: String): String? {
        var mnemonicValue: String? = null
        getAccountMnemonic(address).use(
            onSuccess = { mnemonic ->
                mnemonicValue = mnemonic.words.joinToString(" ")
            },
            onFailed = { _, _ -> return@use null },
        )
        return mnemonicValue
    }

    /**
     * Sign FIDO2 Challenge
     *
     * Signs the FIDO2 challenge with the Algorand account
     * This is used in the liquid FIDO2 extension
     */
    suspend fun signFido2Challenge(
        challenge: ByteArray,
        address: String,
    ): ByteArray? {
        val localAccount = getLocalAccount(address) ?: return null

        return when (localAccount) {
            is LocalAccount.Algo25 -> {
                val mnemonic = getMnemonic(address) ?: return null
                // Use KeyPairs.rawSignBytes for AVM-compatible signing (same as AnswerActivity)
                val keyPair = KeyPairs.getKeyPair(mnemonic)
                KeyPairs.rawSignBytes(challenge, keyPair.private)
            }

            is LocalAccount.HdKey -> {
                val seed = getSeed(localAccount.seedId) ?: return null
                // Use signHdKeyData for AVM-compatible signing without prefix
                signHdKeyData(
                    challenge,
                    seed,
                    localAccount.account,
                    localAccount.change,
                    localAccount.keyIndex
                )
            }

            is LocalAccount.Falcon24 -> {
                // Falcon24 uses a different signing approach
                val privateKey = getFalcon24SecretKey(address) ?: return null
                signFalcon24ArbitraryData(challenge, localAccount.publicKey, privateKey)
            }

            else -> null
        }
    }

    /**
     * Get Account Type String for FIDO2
     *
     * Returns the appropriate type string for liquidExtJSON based on the account type
     * - "falcon-1024" for Falcon24 accounts
     * - "algorand" for all other account types (Algo25, HdKey, etc.)
     */
    suspend fun getAccountTypeForFido2(address: String): String {
        val localAccount = getLocalAccount(address)
        return when (localAccount) {
            is LocalAccount.Falcon24 -> "falcon-1024"
            else -> "algorand"
        }
    }

    suspend fun getAccountPublicKey(address: String): ByteArray {
        val localAccount = getLocalAccount(address)
        return when (localAccount) {
            is LocalAccount.Falcon24 -> localAccount.publicKey
            is LocalAccount.HdKey -> localAccount.publicKey
            is LocalAccount.Algo25 -> Account(getMnemonic(localAccount.algoAddress)).ed25519PublicKey.bytes
            else -> ByteArray(0)
        }
    }

    // ==================== AVMProvider Message Handling ====================

    /**
     * Decode Unsigned Transaction
     */
    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeUnsignedTransaction(unsignedTxn: String): Transaction? =
        Encoder.decodeFromMsgPack(Base64.decode(unsignedTxn), Transaction::class.java)

    /**
     * Handle Messages from DataChannel
     *
     * Processes incoming messages and handles transaction signing requests
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun handleMessages(
        msgStr: String,
        onSignTransaction: (SignTransactionsParams, Message) -> Unit,
    ) {
        try {
            Log.d(TAG, "========================================")
            Log.d(TAG, "📨 RECEIVED MESSAGE FROM DATACHANNEL")
            Log.d(TAG, "Message length: ${msgStr.length}")
            Log.d(TAG, "========================================")

            val message = Message(Base64.UrlSafe.decode(msgStr), EncoderType.CBOR)
            val request = encoder.decode<RequestMessage>(message.data, message.encoding)

            Log.d(TAG, "Message decoded - Reference: ${request.reference}")
            Log.d(TAG, "Request ID: ${request.id}")

            if (request.reference == "arc0027:sign_transactions:request") {
                Log.d(TAG, "✅ Transaction signing request detected")
                viewModelScope.launch {
                    val params =
                        encoder.decode<SignTransactionsParams>(
                            encoder.encode(request.params, EncoderType.NONE),
                            EncoderType.NONE,
                        )
                    Log.d(TAG, "Decoded ${params.txns.size} transaction(s) from request")
                    Log.d(TAG, "Provider ID: ${params.providerId}")

                    // Callback to handle the transaction signing
                    onSignTransaction(params, message)
                }
            } else {
                Log.w(TAG, "⚠️ Unknown request reference: ${request.reference}")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "❌ Error handling message: $e")
            e.printStackTrace()
        }
    }

    /**
     * Handle Message
     *
     * Processes incoming messages and returns appropriate response
     */
    fun handleMessage(message: Message): Any {
        val decoded = encoder.decode<RequestMessage>(message.data, message.encoding)
        when (decoded.reference) {
            "arc0027:sign_transactions:request" -> {
                val params =
                    encoder.decode<SignTransactionsParams>(
                        encoder.encode(decoded.params, EncoderType.NONE),
                        EncoderType.NONE,
                    )
                // Note: processSignTransactions is now suspend, but handleMessage is not
                // This will need to be called from a coroutine context
                val result =
                    kotlinx.coroutines.runBlocking {
                        processSignTransactions(params)
                    }
                return ResponseMessage(
                    id = uuidGenerator.generate().toString(),
                    reference = "arc0027:sign_transactions:response",
                    requestId = decoded.id,
                    result = result,
                )
            }

            else -> {
                throw IllegalArgumentException("Invalid reference: ${decoded.reference}")
            }
        }
    }

    /**
     * Process ARC27 Sign Transactions Requests
     */
    @OptIn(ExperimentalEncodingApi::class)
    suspend fun processSignTransactions(params: SignTransactionsParams): SignTransactionsResult {
        return processSignTransactionsUseCase(
            params = params,
            providerId = providerId,
            accountAddress = _accountAddress.value
        )
    }

    // ==================== Liquid Auth API Methods ====================

    /**
     * Post Attestation Options
     *
     * Retrieves PublicKeyCredentialCreationOptions from the FIDO2 server
     */
    suspend fun fetchAttestationOptions(
        origin: String,
        userAgent: String,
        options: JSONObject = JSONObject(),
    ): Response = attestationApiUseCase.postAttestationOptions(origin, userAgent, options)

    /**
     * Post Attestation Result
     *
     * Submits the PublicKeyCredential to the FIDO2 server after registration
     */
    suspend fun submitAttestationResult(
        origin: String,
        userAgent: String,
        credential: PublicKeyCredential,
        liquidExt: JSONObject? = null,
    ): Response = attestationApiUseCase.postAttestationResult(origin, userAgent, credential, liquidExt)

    /**
     * Post Assertion Options
     *
     * Retrieves PublicKeyCredentialRequestOptions from the FIDO2 server
     */
    suspend fun fetchAssertionOptions(
        origin: String,
        userAgent: String,
        credentialId: String,
        liquidExt: Boolean? = true,
    ): Response = assertionApiUseCase.postAssertionOptions(origin, userAgent, credentialId, liquidExt)

    /**
     * Post Assertion Result
     *
     * Submits the PublicKeyCredential to the FIDO2 server after authentication
     */
    suspend fun submitAssertionResult(
        origin: String,
        userAgent: String,
        credential: PublicKeyCredential,
        liquidExt: JSONObject?,
    ): Response = assertionApiUseCase.postAssertionResult(origin, userAgent, credential, liquidExt)

    // ==================== Biometric Transaction Management ====================

    /**
     * Show transaction confirmation dialog
     *
     * Delegates to use case for displaying confirmation dialog
     */
    fun showTransactionConfirmationDialog(
        context: Context,
        params: SignTransactionsParams,
        onConfirm: () -> Unit,
        onCancel: () -> Unit,
    ) {
        showTransactionConfirmationDialogUseCase(
            context = context,
            params = params,
            onConfirm = onConfirm,
            onCancel = onCancel
        )
    }

    /**
     * Process biometric transaction signing
     *
     * Orchestrates the complete flow of biometric authentication and transaction signing
     * through the use case layer. Emits ViewEvents based on the result.
     */
    suspend fun processBiometricTransactionSigning(
        activity: androidx.fragment.app.FragmentActivity,
        params: SignTransactionsParams,
        message: Message,
    ) {
        when (val result = processBiometricTransactionSigningUseCase(
            activity = activity,
            viewModel = this,
            params = params,
            message = message
        )) {
            is ProcessBiometricTransactionSigningUseCase.Result.Success -> {
                eventDelegate.sendEvent(
                    ViewEvent.TransactionSigned(
                        result.resultMessage,
                        result.signResult
                    )
                )
                eventDelegate.sendEvent(ViewEvent.ShowToast("✅ Transaction signed successfully!"))
            }

            is ProcessBiometricTransactionSigningUseCase.Result.Cancelled -> {
                eventDelegate.sendEvent(ViewEvent.ShowToast(result.reason))
            }

            is ProcessBiometricTransactionSigningUseCase.Result.Error -> {
                eventDelegate.sendEvent(ViewEvent.ShowError(result.message))
            }
        }
    }

    /**
     * Prepare passkey registration
     *
     * Handles the complete flow of fetching attestation options and preparing
     * for FIDO2 registration through the use case layer. Returns result for further processing.
     */
    suspend fun preparePasskeyRegistration(
        authMessage: AuthMessage,
        algoAddress: String,
        options: JSONObject = JSONObject(),
        onSessionUpdate: (String?) -> Unit = {}
    ): RegisterPasskeyUseCase.Result {

        val result = registerPasskeyUseCase(
            authMessage = authMessage,
            algoAddress = algoAddress,
            viewModel = this,
            options = options,
            onSessionUpdate = onSessionUpdate
        )

        when (result) {
            is RegisterPasskeyUseCase.Result.Success -> {
                // Success - caller will handle navigation to registration intent
            }

            is RegisterPasskeyUseCase.Result.Error -> {
                eventDelegate.sendEvent(ViewEvent.ShowError(result.message))
            }
        }

        return result
    }

    fun registerPasskey(
        authMessage: AuthMessage,
        algoAddress: String,
        options: JSONObject = JSONObject(),
    ) {
        viewModelScope.launch {
            val result = preparePasskeyRegistration(
                authMessage = authMessage,
                algoAddress = algoAddress,
                options = options,
                onSessionUpdate = { sessionId ->
                    sessionId?.let { setSession(it) }
                }
            )
            when (result) {
                is RegisterPasskeyUseCase.Result.Success -> {
                    setAttestationApiResponse(result.attestationApiResponse)
                    eventDelegate.sendEvent(
                        ViewEvent.RegistrationSuccess(
                            result.pubKeyCredentialCreationOptions,
                            algoAddress
                        )
                    )
                }
                is RegisterPasskeyUseCase.Result.Error -> {
                    eventDelegate.sendEvent(ViewEvent.ShowError(result.message))
                }
            }
        }
    }

    /**
     * Prepare authentication
     *
     * Fetches assertion options and prepares for FIDO2 authentication.
     * Returns result for further processing.
     */
    suspend fun prepareAuthentication(
        authMessage: AuthMessage,
        credentialId: String,
        onSessionUpdate: (String?) -> Unit = {},
        onCredentialNotFound: () -> Unit = {}
    ): PrepareAuthenticationUseCase.Result {

        val result = prepareAuthenticationUseCase(
            authMessage = authMessage,
            credentialId = credentialId,
            viewModel = this,
            onSessionUpdate = onSessionUpdate,
            onCredentialNotFound = onCredentialNotFound
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
            val result = prepareAuthentication(
                authMessage = authMessage,
                credentialId = credentialId,
                onSessionUpdate = { sessionId -> setSession?.invoke(sessionId) },
                onCredentialNotFound = { onCredentialNotFound?.invoke() }
            )
            when (result) {
                is PrepareAuthenticationUseCase.Result.Success -> {
                    eventDelegate.sendEvent(
                        ViewEvent.AuthenticationSuccess(
                            result.publicKeyCredentialRequestOptions,
                            credentialId
                        )
                    )
                }
                is PrepareAuthenticationUseCase.Result.CredentialNotFound -> {
                    eventDelegate.sendEvent(ViewEvent.ShowError(result.message))
                }
                is PrepareAuthenticationUseCase.Result.Error -> {
                    eventDelegate.sendEvent(ViewEvent.ShowError(result.message))
                }
            }
        }
    }

    fun logAppSignature(context: Context) {
        logAppSignatureUseCase(context, context.javaClass.simpleName)
    }

    fun getAttestationIntentLauncher(
        activity: AppCompatActivity,
        callback: (HandleAttestationResultUseCase.Result) -> Unit
    ): ActivityResultLauncher<IntentSenderRequest> {
        return attestationIntentLauncherUseCase(activity, this, callback)
    }

    fun getAssertionIntentLauncher(
        activity: AppCompatActivity,
        callback: (HandleAssertionResultUseCase.Result) -> Unit
    ): ActivityResultLauncher<IntentSenderRequest> {
        return assertionIntentLauncherUseCase(activity, this,callback)
    }

    fun handleAssertionResultFromLauncher(result: HandleAssertionResultUseCase.Result) {
        viewModelScope.launch {
            when (result) {
                is HandleAssertionResultUseCase.Result.Success -> {
                    eventDelegate.sendEvent(ViewEvent.ShowToast("Authentication Successful!"))
                    eventDelegate.sendEvent(ViewEvent.AssertionSuccess(result.credential))
                }
                is HandleAssertionResultUseCase.Result.Cancelled -> {
                    eventDelegate.sendEvent(ViewEvent.ShowToast(result.message))
                }
                is HandleAssertionResultUseCase.Result.Error -> {
                    eventDelegate.sendEvent(ViewEvent.ShowError(result.message))
                }
            }
        }
    }

    /**
     * Create a Notification Channel
     *
     * This notification channel is used to group notifications from the Liquid WebRTC Service
     */
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

    /**
     * Create a Notification Builder with Defaults
     *
     * This notification builder is used to communicate to the user when the peer connection state changes.
     * It also relays transaction messages that need to be signed to the AnswerActivity
     */
    fun createNotificationBuilder(
        context: Context,
        contentText: String = "Tap to open the app.",
        contentTitle: String = "Liquid Auth",
        channelId: String = NOTIFICATION_CHANNEL_ID,
    ): Builder =
        Builder(context, channelId)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setColor(
                ContextCompat.getColor(
                    context,
                    androidx.biometric.R.color.biometric_error_color
                )
            )
            .setSmallIcon(R.drawable.ic_key)

    private var attestationApiResponse: String? = null
    fun getAttestationApiResponse(): String? = attestationApiResponse
    fun setAttestationApiResponse(value: String?) {
        attestationApiResponse = value
    }

    fun handleAttestationResultFromLauncher(
        result: HandleAttestationResultUseCase.Result,
        algoAddress: String?
    ) {
        when (result) {
            is HandleAttestationResultUseCase.Result.Success -> {
                if (algoAddress != null && getAttestationApiResponse() != null) {
                    viewModelScope.launch {
                        saveCredential(
                            account = algoAddress,
                            credential = result.credential,
                            response = getAttestationApiResponse()!!,
                        )
                        eventDelegate.sendEvent(ViewEvent.AttestationSuccess(result.credential))
                    }
                } else {
                    viewModelScope.launch { eventDelegate.sendEvent(ViewEvent.AttestationError("Missing account or API response for credential save")) }
                }
            }

            is HandleAttestationResultUseCase.Result.Cancelled -> {
                viewModelScope.launch { eventDelegate.sendEvent(ViewEvent.AttestationCancelled) }
            }

            is HandleAttestationResultUseCase.Result.Error -> {
                viewModelScope.launch { eventDelegate.sendEvent(ViewEvent.AttestationError(result.message)) }
            }
        }
    }

    fun getProvideHttpClient(): OkHttpClient =  providerHttpClientUseCase.invoke()

    sealed interface ViewEvent {
        data class ShowToast(val message: String) : ViewEvent
        data class TransactionSigned(
            val resultMessage: ResponseMessage,
            val signResult: SignTransactionsResult
        ) : ViewEvent

        data class ShowError(val message: String) : ViewEvent
        data class AttestationSuccess(val credential: PublicKeyCredential) : ViewEvent
        object AttestationCancelled : ViewEvent
        data class AttestationError(val message: String) : ViewEvent
        data class AssertionSuccess(val credential: PublicKeyCredential) : ViewEvent
        data class AuthenticationSuccess(
            val publicKeyCredentialRequestOptions: com.google.android.gms.fido.fido2.api.common.PublicKeyCredentialRequestOptions,
            val credentialId: String
        ) : ViewEvent
        data class RegistrationSuccess(
            val pubKeyCredentialCreationOptions: com.google.android.gms.fido.fido2.api.common.PublicKeyCredentialCreationOptions,
            val algoAddress: String
        ) : ViewEvent
    }
}
