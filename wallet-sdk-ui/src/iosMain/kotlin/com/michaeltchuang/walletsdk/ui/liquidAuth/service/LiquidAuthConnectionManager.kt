package com.michaeltchuang.walletsdk.ui.liquidAuth.service

import com.michaeltchuang.walletsdk.core.railmpp.core.PaymentRequest
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.LiquidAuthOfferViewModel
import com.michaeltchuang.walletsdk.ui.liquidStream.domain.model.IceConnectionType
import com.michaeltchuang.walletsdk.ui.liquidStream.domain.model.displayName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

var activeIOSBroadcastConnectionManager: IOSLiquidAuthConnectionManager? = null
var iosBroadcastStartHandler: ((origin: String, requestId: String) -> Unit)? = null
var iosBroadcastStopHandler: (() -> Unit)? = null
var iosBroadcastSendMessageHandler: ((message: String) -> Unit)? = null
var iosBroadcastIsConnectedHandler: (() -> Boolean)? = null
var iosBroadcastDetectConnectionTypeHandler: (() -> String)? = null

private const val TAG = "IOSLiquidAuthCM"
private const val CONNECTION_TYPE_POLL_INTERVAL_MS = 1000L

class IOSLiquidAuthConnectionManager : LiquidAuthConnectionManager {

    private val _connectionType = MutableStateFlow(IceConnectionType.UNKNOWN)
    override val connectionType: StateFlow<IceConnectionType> = _connectionType

    private var viewModel: LiquidAuthOfferViewModel? = null
    private var activeRequestId: String? = null
    private var connectionTypePollingJob: Job? = null

    private var activeViewerAddressForVault: String? = null
    private var activeViewerAuthorizedSignerKey: ByteArray? = null
    private var activePaymentSessionId: String? = null
    private var activePaymentRecipient: String? = null
    private var activePaymentAmount: String? = null
    private var activeCreatorVoucherClaimSnapshot: CreatorVoucherClaimSnapshot? = null

    data class CreatorVoucherClaimSnapshot(
        val sessionId: String,
        val viewerAddress: String,
        val viewerPublicKeyBase64: String,
        val signatureBase64: String,
        val totalAmountClaimedMicroUsdc: Long,
    )

    override fun initialize(viewModel: LiquidAuthOfferViewModel) {
        this.viewModel = viewModel
        activeIOSBroadcastConnectionManager = this
        println("$TAG: initialize() viewModel=$viewModel")
    }

    override fun startListening(
        origin: String,
        requestId: String,
    ) {
        val handler = iosBroadcastStartHandler
        if (handler == null) {
            println("$TAG: ⚠️ iosBroadcastStartHandler not set!")
            return
        }
        if (viewModel == null) {
            println("$TAG: ❌ viewModel is null — call initialize() first")
            return
        }
        if (isConnected() && activeRequestId == requestId) {
            println("$TAG: already connected for requestId=$requestId, skipping")
            return
        }
        if (activeRequestId != null && activeRequestId != requestId) {
            println("$TAG: 🔁 requestId changed ($activeRequestId → $requestId), restarting")
            stopListening()
        }
        println("$TAG: startListening() origin=$origin requestId=$requestId")
        activeRequestId = requestId
        handler(origin, requestId)
    }

    override fun stopListening() {
        println("$TAG: stopListening() (activeRequestId=$activeRequestId)")
        stopConnectionTypePolling()
        stopBlockConsumption()
        iosBroadcastStopHandler?.invoke()
        activeRequestId = null
        activeViewerAddressForVault = null
        activeViewerAuthorizedSignerKey = null
        activePaymentSessionId = null
        activePaymentRecipient = null
        activePaymentAmount = null
        activeCreatorVoucherClaimSnapshot = null
        _connectionType.value = IceConnectionType.UNKNOWN
    }

    override fun sendMessage(message: String) {
        val handler = iosBroadcastSendMessageHandler
        if (handler == null) {
            println("$TAG: sendMessage skipped — iosBroadcastSendMessageHandler not set")
            return
        }
        handler(message)
    }

    @OptIn(ExperimentalEncodingApi::class)
    override fun sendVideoFrame(
        frameId: String,
        timestamp: Long,
        frameData: ByteArray,
        width: Int,
        height: Int,
        format: String,
    ) {
        if (!isConnected()) {
            println("$TAG: sendVideoFrame skipped — not connected")
            return
        }
        try {
            val base64Data = Base64.encode(frameData)
            val hostAddress = activePaymentRecipient.orEmpty()
            val sessionId = activePaymentSessionId.orEmpty()
            val hostJsonField = if (hostAddress.isNotBlank()) ""","hostAddress":"$hostAddress"""" else ""
            val sessionJsonField = if (sessionId.isNotBlank()) ""","sessionId":"$sessionId"""" else ""
            val jsonMessage =
                """{"reference":"liquid:video:frame","id":"$frameId","timestamp":$timestamp,"format":"$format","data":"$base64Data","width":$width,"height":$height$hostJsonField$sessionJsonField}"""
            println("$TAG: 🎥 sendVideoFrame ${width}x$height (${frameData.size} bytes)")
            sendMessage(jsonMessage)
        } catch (e: Exception) {
            println("$TAG: ❌ sendVideoFrame failed: $e")
        }
    }

    override fun isConnected(): Boolean = iosBroadcastIsConnectedHandler?.invoke() ?: false

    override fun sendPaymentRequest(paymentRequest: PaymentRequest) {
        println(
            "$TAG: sendPaymentRequest — X402 not available on iOS, bypassing payment. " +
                "network=${paymentRequest.network} amount=${paymentRequest.amount}",
        )
        activePaymentSessionId = activePaymentSessionId ?: paymentRequest.id
        activePaymentRecipient = paymentRequest.payTo
        activePaymentAmount = paymentRequest.amount
        viewModel?.startVideoStreaming()
    }

    override fun startBlockConsumption(sessionId: String) {
        println("$TAG: startBlockConsumption — not available on iOS (session=$sessionId)")
    }

    override fun stopBlockConsumption() {
        println("$TAG: stopBlockConsumption — no-op on iOS")
    }

    @Suppress("unused")
    fun notifyClientConnected(requestId: String) {
        println("$TAG: ✅ notifyClientConnected requestId=$requestId")
        viewModel?.onClientConnected(requestId)
        startConnectionTypePolling()
    }

    @Suppress("unused")
    fun notifyClientDisconnected() {
        println("$TAG: notifyClientDisconnected")
        stopConnectionTypePolling()
        stopBlockConsumption()
        viewModel?.onClientDisconnected()
    }

    @Suppress("unused")
    fun notifyMessageReceived(message: String) {
        println("$TAG: 📨 notifyMessageReceived len=${message.length} preview=${message.take(120)}")
        tryCaptureViewerAddressFromMessage(message)
        val requestId = activeRequestId
        if (requestId != null && isConnected()) {
            viewModel?.onClientConnected(requestId)
        }
    }

    @Suppress("unused")
    fun notifyConnectionTypeChanged(typeString: String) {
        val type = parseConnectionType(typeString)
        if (_connectionType.value != type) {
            _connectionType.value = type
            println("$TAG: 🌐 connection type → ${type.displayName()}")
            viewModel?.onConnectionTypeChanged(type)
        }
    }

    private fun tryCaptureViewerAddressFromMessage(msg: String) {
        runCatching {
            val voucherRef = msg.jsonOptString("reference") ?: return@runCatching

            if (voucherRef == "liquid:viewer:hello") {
                val helloViewer = msg.jsonOptString("viewer")
                val helloPublicKeyBase64 = msg.jsonOptString("viewerPublicKey")
                if (helloPublicKeyBase64 != null) {
                    val signerKey = decodeBase64OrNull(helloPublicKeyBase64)
                    if (signerKey != null) {
                        if (helloViewer != null && helloViewer != activeViewerAddressForVault) {
                            activeViewerAddressForVault = helloViewer
                            println("$TAG: [VIEWER_HELLO_ADDR] viewer=$helloViewer")
                        }
                        activeViewerAuthorizedSignerKey = signerKey
                        println(
                            "$TAG: [VIEWER_HELLO_KEY] viewer=$helloViewer " +
                                "keyLen=${signerKey.size} session=$activePaymentSessionId",
                        )
                    }
                }
            }

            if (voucherRef == "liquid:payment:voucher") {
                val signature = msg.jsonOptString("signature")
                val claimedAmount = msg.jsonOptLong("totalAmountClaimedMicroUsdc")
                val voucherSessionId = msg.jsonOptString("id")
                val voucherViewer = msg.jsonOptString("viewer")
                val voucherViewerPublicKey = msg.jsonOptString("viewerPublicKey")

                if (signature == null || claimedAmount == null || voucherSessionId == null ||
                    voucherViewer == null || voucherViewerPublicKey == null
                ) {
                    println(
                        "$TAG: [VOUCHER_SKIP] reason=invalid_payload " +
                            "session=${msg.jsonOptString("id")} claimedAmount=$claimedAmount",
                    )
                } else {
                    val activeSession = activePaymentSessionId
                    if (activeSession != null && voucherSessionId != activeSession) {
                        println(
                            "$TAG: [VOUCHER_SKIP] reason=session_mismatch " +
                                "voucherSession=$voucherSessionId activeSession=$activeSession",
                        )
                    } else {
                        val previousClaimedAmount =
                            activeCreatorVoucherClaimSnapshot?.totalAmountClaimedMicroUsdc
                        if (previousClaimedAmount != null && claimedAmount < previousClaimedAmount) {
                            println(
                                "$TAG: [VOUCHER_STALE_SKIP] session=$voucherSessionId " +
                                    "claimed=$claimedAmount previous=$previousClaimedAmount",
                            )
                        } else {
                            activeCreatorVoucherClaimSnapshot =
                                CreatorVoucherClaimSnapshot(
                                    sessionId = voucherSessionId,
                                    viewerAddress = voucherViewer,
                                    viewerPublicKeyBase64 = voucherViewerPublicKey,
                                    signatureBase64 = signature,
                                    totalAmountClaimedMicroUsdc = claimedAmount,
                                )
                            println(
                                "$TAG: [VOUCHER_CAPTURED] session=$voucherSessionId " +
                                    "sigLen=${signature.length} claimedMicroUsdc=$claimedAmount " +
                                    "viewer=$voucherViewer",
                            )
                            startBlockConsumption(voucherSessionId)
                        }
                    }
                }
            }

            val candidate = msg.jsonOptString("address")
            if (candidate != null && candidate != activeViewerAddressForVault) {
                activeViewerAddressForVault = candidate
                println("$TAG: 🔑 viewer address captured from message: $candidate")
            }
        }.onFailure { e ->
            println("$TAG: ⚠️ tryCaptureViewerAddressFromMessage error: $e")
        }
    }

    private fun startConnectionTypePolling() {
        println("$TAG: 🔄 starting connection-type polling")
        connectionTypePollingJob?.cancel()
        connectionTypePollingJob =
            CoroutineScope(Dispatchers.Default).launch {
                while (isActive) {
                    val detectHandler = iosBroadcastDetectConnectionTypeHandler
                    if (detectHandler != null) {
                        notifyConnectionTypeChanged(detectHandler())
                    }
                    delay(CONNECTION_TYPE_POLL_INTERVAL_MS)
                }
            }
    }

    private fun stopConnectionTypePolling() {
        connectionTypePollingJob?.cancel()
        connectionTypePollingJob = null
        _connectionType.value = IceConnectionType.UNKNOWN
    }

    private fun parseConnectionType(typeString: String): IceConnectionType =
        when (typeString.trim().lowercase()) {
            "local" -> IceConnectionType.LOCAL
            "stun" -> IceConnectionType.STUN
            "relay" -> IceConnectionType.RELAY
            "failed" -> IceConnectionType.FAILED
            else -> IceConnectionType.UNKNOWN
        }

    private fun String.jsonOptString(key: String): String? =
        Regex(""""$key"\s*:\s*"([^"]*)"""")
            .find(this)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotEmpty() }

    private fun String.jsonOptLong(key: String): Long? =
        Regex(""""$key"\s*:\s*(-?\d+)""")
            .find(this)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeBase64OrNull(value: String): ByteArray? =
        runCatching {
            val normalised = value.replace('-', '+').replace('_', '/').trimEnd('=')
            val padded = normalised + "=".repeat((4 - normalised.length % 4) % 4)
            Base64.decode(padded)
        }.getOrNull()
}

actual fun createLiquidAuthConnectionManager(platformContext: Any): LiquidAuthConnectionManager =
    IOSLiquidAuthConnectionManager()
