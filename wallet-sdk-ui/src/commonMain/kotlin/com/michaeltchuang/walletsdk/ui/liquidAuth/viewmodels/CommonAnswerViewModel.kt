package com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels

import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAccountAlgoBalance
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAlgo25SecretKey
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetFalcon24SecretKey
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetHdSeed
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetLocalAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetLocalAccounts
import com.michaeltchuang.walletsdk.core.algosdk.signAlgo25ArbitraryData
import com.michaeltchuang.walletsdk.core.algosdk.signFalcon24ArbitraryData
import com.michaeltchuang.walletsdk.core.algosdk.signHdKeyData
import com.michaeltchuang.walletsdk.core.network.domain.usecase.GetCurrentNetworkUseCase
import com.michaeltchuang.walletsdk.core.network.usecase.GetCurrentBlockUseCase
import com.michaeltchuang.walletsdk.core.railmpp.MppNetworks
import com.michaeltchuang.walletsdk.core.railmpp.core.DCMessageType
import com.michaeltchuang.walletsdk.core.railmpp.utils.RailMppConstants
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetRemainingSessionVaultBalanceUseCase
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetSessionVaultConfigUseCase
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.MppWalletSignerUseCase
import com.michaeltchuang.walletsdk.core.railmpp.utils.MppPayments
import com.michaeltchuang.walletsdk.ui.liquidStream.domain.usecases.SetupMppPaymentViewerUseCase
import com.michaeltchuang.walletsdk.utils.DataResource
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.roundToLong

open class CommonAnswerViewModel(
    private val getCurrentBlockUseCase: GetCurrentBlockUseCase,
    protected val getAccountAlgoBalance: GetAccountAlgoBalance,
    protected val getLocalAccount: GetLocalAccount,
    protected val getLocalAccounts: GetLocalAccounts,
    protected val getAlgo25SecretKey: GetAlgo25SecretKey,
    protected val getFalcon24SecretKey: GetFalcon24SecretKey,
    protected val getSeed: GetHdSeed,
    protected val getCurrentNetworkUseCase: GetCurrentNetworkUseCase,
    private val getRemainingSessionVaultBalanceUseCase: GetRemainingSessionVaultBalanceUseCase,
    private val getSessionVaultConfigUseCase: GetSessionVaultConfigUseCase,
    protected val setupMppPaymentViewerUseCase: SetupMppPaymentViewerUseCase,
    private val mppWalletSignerUseCase: MppWalletSignerUseCase,
) : LiquidAuthViewerStateHolder() {
    companion object {
        private const val TAG = "CommonAnswerViewModel"
        private const val MIN_CBOR_REQUEST_MESSAGE_BYTES = 8
    }

    // ── DataChannel message handling ───────────────────────────────────────────

    private val json = Json { ignoreUnknownKeys = true }

    private val _pendingSignTransactionRequest = MutableStateFlow<PendingSignTransactionRequest?>(null)
    val pendingSignTransactionRequest: StateFlow<PendingSignTransactionRequest?> = _pendingSignTransactionRequest

    var showConfirmationDialog = MutableStateFlow(false)

    /**
     * Shared DataChannel message handling used by Android and iOS.
     *
     * JSON video/payment envelopes are parsed in common. CBOR/ARC-0027 payloads are routed to
     * [handleCborRequestMessage], which platforms can implement with their provider codec.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun handleMessages(
        msgStr: String,
        onVideoFrame: ((VideoFrameData) -> Unit)? = null,
    ) {
        handleDataChannelMessage(
            msgStr = msgStr,
            onVideoFrame = onVideoFrame,
        )
    }

    protected fun handleDataChannelMessage(
        msgStr: String,
        onSignTransaction: ((PendingSignTransactionRequest) -> Unit)? = null,
        onVideoFrame: ((VideoFrameData) -> Unit)? = null,
    ) {
        try {

            if (msgStr.trimStart().startsWith("{")) {
                handleJsonDataChannelMessage(msgStr, onVideoFrame)
                return
            }

            val cborBytes =
                Base64.Default.UrlSafe
                    .withPadding(Base64.PaddingOption.ABSENT)
                    .decode(msgStr)

            if (cborBytes.isNotEmpty()) {
                val firstBytes = cborBytes.take(10).joinToString(" ") { it.toHexByteString() }
                Napier.d(tag = TAG, message = "Incoming CBOR first bytes: $firstBytes")
                Napier.d(
                    tag = TAG,
                    message =
                        "Incoming CBOR encoding: " +
                            if (cborBytes[0].toInt() and 0x1F == 0x1F) "INDEFINITE-LENGTH" else "DEFINITE-LENGTH",
                )
            }

            if (cborBytes.size < MIN_CBOR_REQUEST_MESSAGE_BYTES) {
                Napier.w(
                    tag = TAG,
                    message = "Ignoring non-request DataChannel payload: decodedBytes=${cborBytes.size}",
                )
                return
            }

            viewModelScope.launch {
                val request = handleCborRequestMessage(cborBytes)
                when (request) {
                    is DataChannelRequest.SignTransactions -> {
                        _pendingSignTransactionRequest.value = request.pendingRequest
                        showConfirmationDialog.value = true
                        onSignTransaction?.invoke(request.pendingRequest)
                    }

                    is DataChannelRequest.VideoFrame -> handleVideoFrameMessage(request.payload, onVideoFrame)
                    null -> Unit
                }
            }
        } catch (e: Throwable) {
            Napier.e(tag = TAG, message = "Error handling message: $e")
            e.printStackTrace()
        }
    }

    private fun handleJsonDataChannelMessage(
        msgStr: String,
        onVideoFrame: ((VideoFrameData) -> Unit)?,
    ) {
        val jsonObject = json.parseToJsonElement(msgStr).jsonObject
        when (val reference = jsonObject.optString("reference")) {
            "liquid:video:frame" -> {
                handleVideoFrameMessage(msgStr, onVideoFrame)
            }

            "liquid:payment:balance",
            "liquid:payment:voucher",
            "liquid:payment:depleted",
            -> {
                Napier.d(tag = TAG, message = "Liquid payment JSON message: $reference")
            }

            else -> Napier.w(tag = TAG, message = "Unknown JSON message reference: $reference")
        }
    }

    private fun handleVideoFrameMessage(
        msgStr: String,
        onVideoFrame: ((VideoFrameData) -> Unit)?,
    ) {
        try {
            val jsonObject = json.parseToJsonElement(msgStr).jsonObject
            val dataBase64 = jsonObject.reqString("data")
            val frameData = Base64.Default.decode(dataBase64)
            val videoFrame =
                VideoFrameData(
                    id = jsonObject.reqString("id"),
                    timestamp = jsonObject.reqString("timestamp").toLong(),
                    data = frameData,
                    width = jsonObject.reqInt("width"),
                    height = jsonObject.reqInt("height"),
                    format = jsonObject.optString("format") ?: "jpeg",
                )
            setVideoFrame(videoFrame)
            onVideoFrame?.invoke(videoFrame)
        } catch (e: Exception) {
            Napier.e(tag = TAG, message = "Failed to decode video frame: $e")
        }
    }

    fun clearPendingSignRequest() {
        _pendingSignTransactionRequest.value = null
        showConfirmationDialog.value = false
    }

    fun handleViewerTransportMessage(
        message: String,
        onPongRequested: () -> Unit,
        onLegacyPaymentRequest: (message: String) -> Unit,
        onPaymentMessage: (message: String) -> Boolean,
        onHostDiscovered: (hostAddress: String?) -> Unit = {},
    ) {
        when (message.jsonOptString("reference")) {
            "liquid:video:frame" -> handleViewerSharedMessage(message, onHostDiscovered)
            "liquid:payment:request" -> handleLegacyViewerPaymentRequest(message, onLegacyPaymentRequest, onHostDiscovered)
            "ping" -> onPongRequested()
            null -> handleViewerPaymentMessage(message, onPaymentMessage, onHostDiscovered)
            else -> Unit
        }
    }

    fun applyViewerSharedMessageState(message: String) {
        val hostAddress = message.jsonOptString("hostAddress")
        val sessionId = message.jsonOptString("sessionId")
        applyViewerMessageSessionState(hostAddress, sessionId)
    }

    fun applyViewerSegmentRequestState(message: String): String? {
        val sessionId = message.jsonOptString("sessionId") ?: ""
        val payTo =
            message.jsonOptString("payTo") ?: run {
                val payloadStart = message.indexOf("\"payload\"")
                if (payloadStart >= 0) message.substring(payloadStart).jsonOptString("payTo") ?: "" else ""
            }
        applyViewerMessageSessionState(payTo, sessionId)
        return payTo.takeIf { it.isNotBlank() }
    }

    private fun handleViewerSharedMessage(
        message: String,
        onHostDiscovered: (hostAddress: String?) -> Unit,
    ) {
        val hostAddress = message.jsonOptString("hostAddress")
        applyViewerSharedMessageState(message)
        if (!hostAddress.isNullOrBlank()) onHostDiscovered(hostAddress)
        handleMessages(message)
    }

    private fun handleLegacyViewerPaymentRequest(
        message: String,
        onLegacyPaymentRequest: (message: String) -> Unit,
        onHostDiscovered: (hostAddress: String?) -> Unit,
    ) {
        val sessionId = message.jsonOptString("id")
        val amount = message.jsonOptString("amount") ?: ""
        val payTo = message.jsonOptString("payTo") ?: ""
        val asset = message.jsonOptString("asset") ?: "USDC"

        applyViewerMessageSessionState(payTo, sessionId)
        if (payTo.isNotBlank()) onHostDiscovered(payTo)

        viewModelScope.launch {
            val existing = getExistingViewerSessionVaultBalance(payTo)
            if (existing > 0L) {
                setViewerSessionVaultBalance(existing)
                approveFundedViewerConsent(existing)
            } else {
                showPendingViewerConsent(
                    amount = amount,
                    asset = asset,
                    payTo = payTo,
                )
            }
        }
        onLegacyPaymentRequest(message)
    }

    private suspend fun getExistingViewerSessionVaultBalance(hostAddress: String): Long {
        val viewer = viewerAddress.value
        val host = hostAddress.takeIf { it.isNotBlank() } ?: this.hostAddress.value
        if (viewer.isBlank() || host.isBlank()) return 0L
        return runCatching {
            getRemainingSessionVaultBalanceUseCase(
                GetRemainingSessionVaultBalanceUseCase.Params(
                    viewerAddress = viewer,
                    hostAddress = host,
                    appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                    authorizedSignerPublicKey = null,
                ),
            ).getOrDefault(0L)
        }.getOrDefault(0L)
    }

    private fun handleViewerPaymentMessage(
        message: String,
        onPaymentMessage: (message: String) -> Boolean,
        onHostDiscovered: (hostAddress: String?) -> Unit,
    ) {
        when (val msgType = message.jsonOptString("type")) {
            DCMessageType.SEGMENT_REQUEST,
            DCMessageType.SEGMENT_ACCEPTED,
            DCMessageType.SEGMENT_REJECTED,
            DCMessageType.SESSION_TERMINATE,
            -> {
                if (msgType == DCMessageType.SEGMENT_REQUEST) {
                    applyViewerSegmentRequestState(message)?.let { onHostDiscovered(it) }
                }
                onPaymentMessage(message)
            }
        }
    }

    protected open suspend fun handleCborRequestMessage(cborBytes: ByteArray): DataChannelRequest? = null

    // ── Current block number ───────────────────────────────────────────────────

    private val _currentBlockNumber = MutableStateFlow<Long?>(null)
    val currentBlockNumber: StateFlow<Long?> = _currentBlockNumber

    private var blockNumberPollingJob: Job? = null

    /**
     * Optional iOS-style stream-timeout callback.
     * Set this from the overlay to be notified when [onStreamTimeout] fires.
     * Android overrides [onStreamTimeout] directly; iOS uses this lambda hook.
     */
    var onTimeout: (() -> Unit)? = null

    override fun onStreamTimeout(reason: String) {
        onTimeout?.invoke()
    }

    // ── Balance ────────────────────────────────────────────────────────────────

    fun fetchAccountBalance() {
        viewModelScope.launch {
            try {
                val balance = getAccountAlgoBalance(accountAddress.value)
                setAccountBalance(balance?.toString())
                println("$TAG: fetched balance=${balance?.toString() ?: "0"}")
            } catch (e: Exception) {
                println("$TAG: exception fetching balance: ${e.message}")
            }
        }
    }

    // ── Account helpers ────────────────────────────────────────────────────────
    suspend fun getFee(): String {
        val localAccount = getLocalAccount(accountAddress.value)
        return when (localAccount) {
            is LocalAccount.Falcon24 -> "0.004"
            else -> "0.001"
        }
    }

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
                val secretKey = getAlgo25SecretKey(address)
                if (secretKey != null && secretKey.size == 64) {
                    // Last 32 bytes of the 64-byte expanded key are the public key.
                    secretKey.copyOfRange(32, 64)
                } else {
                    ByteArray(0)
                }
            }
            is LocalAccount.SeedVault -> {
                val decoded = decodeBase58(localAccount.publicKey)
                if (decoded == null || decoded.size != 32) {
                    Napier.e(
                        tag = TAG,
                        message = "Invalid SeedVault public key for address=${localAccount.address}, decodedLength=${decoded?.size}",
                    )
                    ByteArray(0)
                } else {
                    decoded
                }
            }
            else -> ByteArray(0)
        }
    }

    // ── FIDO-2 signing ────────────────────────────────────────────────────────

    /**
     * Signs a FIDO-2 challenge with the key material stored for [address].
     *
     * Delegates to the platform's expect/actual signing functions, so this works on
     * both Android and iOS without any platform-specific code here.
     */
    suspend fun signFido2Challenge(
        challenge: ByteArray,
        address: String,
    ): ByteArray? {
        println("$TAG: signFido2Challenge called for address=$address")
        val localAccount =
            getLocalAccount(address) ?: run {
                println("$TAG: getLocalAccount returned null for $address")
                return null
            }
        println("$TAG: localAccount type=${localAccount::class.simpleName}")

        return when (localAccount) {
            is LocalAccount.Algo25 -> {
                val secretKey =
                    getAlgo25SecretKey(address) ?: run {
                        println("$TAG: getAlgo25SecretKey returned null")
                        return null
                    }
                val result = signAlgo25ArbitraryData(challenge, secretKey)
                println("$TAG: signAlgo25ArbitraryData result=${result != null}")
                result
            }

            is LocalAccount.HdKey -> {
                val seed = getSeed(localAccount.seedId) ?: return null
                signHdKeyData(
                    data = challenge,
                    seed = seed,
                    account = localAccount.account,
                    change = localAccount.change,
                    key = localAccount.keyIndex,
                )
            }

            is LocalAccount.Falcon24 -> {
                val privateKey = getFalcon24SecretKey(address) ?: return null
                if (challenge.isEmpty() || localAccount.publicKey.isEmpty() || privateKey.isEmpty()) {
                    println("$TAG: signFido2Challenge skipped — empty input for Falcon24")
                    return null
                }
                try {
                    signFalcon24ArbitraryData(challenge, localAccount.publicKey, privateKey)
                } catch (t: Throwable) {
                    println("$TAG: signFalcon24ArbitraryData threw: ${t.message}")
                    null
                }
            }

            is LocalAccount.SeedVault -> {
                println("$TAG: SeedVault account — FIDO2 signing not supported")
                null
            }

            else -> null
        }
    }

    // ── MPP viewer/payment helpers ─────────────────────────────────────────────

    suspend fun topUpViewerSessionVault(
        enteredAmount: String,
        viewerAddress: String,
        creatorAddress: String,
        signer: MppWalletSigner,
    ): Result<Long?> {
        val amountUsdc = enteredAmount.toDoubleOrNull()?.takeIf { it > 0.0 } ?: 1.0
        val depositMicroUsdc = (amountUsdc * 1_000_000.0).roundToLong().coerceAtLeast(1L)

        val sessionVaultAppId = getSessionVaultConfigUseCase(getCurrentNetworkUseCase().first()).appId
        val topUpResult =
            runCatching {
                MppPayments
                    .topUpSessionVault(
                        signer = signer,
                        additionalDepositMicroUsdc = depositMicroUsdc,
                    ).getOrThrow()
            }.onFailure { throwable ->
                setupMppPaymentViewerUseCase.clearPendingPayment()
                Napier.e(tag = TAG, message = "[VIEWER_SESSION_VAULT_TOPUP_ERR] viewer=$viewerAddress creator=$creatorAddress", throwable = throwable)
            }

        val txId = topUpResult.getOrElse { return Result.failure(it) }
        setupMppPaymentViewerUseCase.markPaymentPending()
        Napier.e(tag = TAG, message = "[VIEWER_SESSION_VAULT_TOPUP_OK] viewer=$viewerAddress creator=$creatorAddress txId=$txId")

        val onChainRemaining =
            runCatching {
                getRemainingSessionVaultBalanceUseCase(
                    GetRemainingSessionVaultBalanceUseCase.Params(
                        viewerAddress = viewerAddress,
                        hostAddress = creatorAddress,
                        appId = sessionVaultAppId,
                        authorizedSignerPublicKey = signer.authorizedSignerPublicKey,
                    ),
                ).getOrThrow()
            }.onFailure { throwable ->
                Napier.e(
                    tag = TAG,
                    message = "[VIEWER_SESSION_VAULT_TOPUP_REFRESH_ERR] viewer=$viewerAddress creator=$creatorAddress",
                    throwable = throwable,
                )
            }.getOrNull()

        if (onChainRemaining != null) {
            if (onChainRemaining > 0L) {
                setupMppPaymentViewerUseCase.clearPendingPayment()
            }
            setViewerSessionVaultBalance(onChainRemaining)
        }
        return Result.success(onChainRemaining)
    }

    /** Build an [MppWalletSigner] for the given account address. */
    suspend fun buildMppWalletSigner(address: String): MppWalletSigner? = mppWalletSignerUseCase(address)

    fun startViewerOnChainRefresh(
        viewerAddress: String,
        hostAddress: String? = null,
    ) {
        viewModelScope.launch {
            val sessionVaultAppId = getSessionVaultConfigUseCase(getCurrentNetworkUseCase().first()).appId
            setupMppPaymentViewerUseCase.startViewerOnChainRefresh(
                scope = viewModelScope,
                viewerAddress = viewerAddress,
                hostAddress = hostAddress,
                sessionVaultAppId = sessionVaultAppId,
                authorizedSignerPublicKey = null,
                setViewerSessionVaultProgress = ::setViewerSessionVaultProgress,
            )
        }
    }

    fun stopMppPaymentViewer() {
        setupMppPaymentViewerUseCase.stop()
    }

    protected suspend fun resolveMppClientNetwork(address: String): String =
        if (getLocalAccount(address) is LocalAccount.SeedVault) {
            MppNetworks.SOLANA_DEVNET
        } else {
            MppNetworks.ALGORAND_TESTNET
        }

    private fun Byte.toHexByteString(): String {
        val value = toInt() and 0xFF
        val digits = "0123456789ABCDEF"
        return "0x${digits[value shr 4]}${digits[value and 0x0F]}"
    }

    private fun JsonObject.reqString(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull ?: error("Missing '$key'")

    private fun JsonObject.optString(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.ifBlank { null }

    private fun JsonObject.reqInt(key: String): Int =
        this[key]?.jsonPrimitive?.intOrNull ?: error("Missing '$key'")

    private fun String.jsonOptString(key: String): String? =
        Regex(""""$key"\s*:\s*"([^"]*)"""")
            .find(this)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotEmpty() }

    // ── Block number polling ───────────────────────────────────────────────────

    fun startRealtimeBlockNumberUpdates() {
        if (blockNumberPollingJob?.isActive == true) return
        blockNumberPollingJob =
            viewModelScope.launch {
                while (true) {
                    getCurrentBlockUseCase().collect { result ->
                        when (result) {
                            is DataResource.Success -> _currentBlockNumber.value = result.data
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
}

