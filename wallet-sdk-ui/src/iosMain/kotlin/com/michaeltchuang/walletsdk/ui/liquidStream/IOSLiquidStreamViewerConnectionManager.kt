package com.michaeltchuang.walletsdk.ui.liquidStream

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

var activeIOSViewerConnectionManager: IOSLiquidStreamViewerConnectionManager? = null
var iosViewerStartHandler: ((origin: String, requestId: String) -> Unit)? = null
var iosViewerStopHandler: (() -> Unit)? = null
var iosViewerSendMessageHandler: ((message: String) -> Unit)? = null
var iosViewerIsConnectedHandler: (() -> Boolean)? = null
var iosViewerDetectConnectionTypeHandler: (() -> String)? = null

private const val TAG = "IOSLiquidStreamViewerCM"
private const val CONNECTION_TYPE_POLL_INTERVAL_MS = 1000L

class IOSLiquidStreamViewerConnectionManager {

    data class VideoFrame(
        val id: String,
        val timestamp: Long,
        val data: ByteArray,
        val width: Int,
        val height: Int,
        val format: String,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is VideoFrame) return false
            return id == other.id &&
                timestamp == other.timestamp &&
                data.contentEquals(other.data) &&
                width == other.width &&
                height == other.height &&
                format == other.format
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

    enum class ConnectionState { IDLE, CONNECTING, CONNECTED, DISCONNECTED }

    private val _connectionState = MutableStateFlow(ConnectionState.IDLE)
    @Suppress("unused")
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _connectionType = MutableStateFlow(IceConnectionType.UNKNOWN)
    @Suppress("unused")
    val connectionType: StateFlow<IceConnectionType> = _connectionType

    private val _latestVideoFrame = MutableStateFlow<VideoFrame?>(null)
    @Suppress("unused")
    val latestVideoFrame: StateFlow<VideoFrame?> = _latestVideoFrame

    private val _sessionId = MutableStateFlow("")
    @Suppress("unused")
    val sessionId: StateFlow<String> = _sessionId

    private val _viewerAddress = MutableStateFlow("")
    @Suppress("unused")
    val viewerAddress: StateFlow<String> = _viewerAddress

    private val _hostAddress = MutableStateFlow("")
    @Suppress("unused")
    val hostAddress: StateFlow<String> = _hostAddress

    private val _remainingBalanceMicroUsdc = MutableStateFlow(0L)
    @Suppress("unused")
    val remainingBalanceMicroUsdc: StateFlow<Long> = _remainingBalanceMicroUsdc

    private var activeOrigin: String? = null
    private var activeRequestId: String? = null
    private var connectionTypePollingJob: Job? = null

    @Suppress("unused")
    private var viewerAuthorizedSignerKey: ByteArray? = null

    fun connect(origin: String, requestId: String, viewerAddress: String = "") {
        val handler = iosViewerStartHandler
        if (handler == null) {
            println("$TAG: ⚠️ iosViewerStartHandler not set")
            return
        }
        activeIOSViewerConnectionManager = this
        activeOrigin = origin
        activeRequestId = requestId
        if (viewerAddress.isNotBlank()) _viewerAddress.value = viewerAddress
        _connectionState.value = ConnectionState.CONNECTING
        println("$TAG: connect() origin=$origin requestId=$requestId")
        handler(origin, requestId)
    }

    fun disconnect() {
        println("$TAG: disconnect()")
        stopConnectionTypePolling()
        iosViewerStopHandler?.invoke()
        activeOrigin = null
        activeRequestId = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _latestVideoFrame.value = null
        _connectionType.value = IceConnectionType.UNKNOWN
        _sessionId.value = ""
        _remainingBalanceMicroUsdc.value = 0L
    }

    @Suppress("unused")
    fun isConnected(): Boolean = iosViewerIsConnectedHandler?.invoke() ?: false

    fun sendMessage(message: String) {
        val handler = iosViewerSendMessageHandler ?: run {
            println("$TAG: sendMessage skipped — handler not set")
            return
        }
        handler(message)
    }

    @Suppress("unused")
    fun notifyConnected() {
        println("$TAG: ✅ notifyConnected")
        _connectionState.value = ConnectionState.CONNECTED
        startConnectionTypePolling()
    }

    @Suppress("unused")
    fun notifyDisconnected() {
        println("$TAG: notifyDisconnected")
        stopConnectionTypePolling()
        _connectionState.value = ConnectionState.DISCONNECTED
        _latestVideoFrame.value = null
    }

    @Suppress("unused")
    fun notifyMessageReceived(message: String) {
        handleMessage(message)
    }

    @Suppress("unused")
    fun notifyConnectionTypeChanged(typeString: String) {
        val type = parseConnectionType(typeString)
        if (_connectionType.value != type) {
            _connectionType.value = type
            println("$TAG: 🌐 connection type → ${type.displayName()}")
        }
    }

    private fun handleMessage(message: String) {
        when (val reference = message.jsonOptString("reference")) {
            "liquid:video:frame" -> handleVideoFrame(message)
            "liquid:payment:request" -> handlePaymentRequest(message)
            "ping" -> {
                println("$TAG: 🏓 ping — sending pong")
                sendMessage("""{"reference":"pong"}""")
            }
            null -> println("$TAG: 📨 message (no ref) preview=${message.take(80)}")
            else -> println("$TAG: 📨 message ref=$reference preview=${message.take(80)}")
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun handleVideoFrame(message: String) {
        runCatching {
            val id = message.jsonOptString("id") ?: return@runCatching
            val timestamp = message.jsonOptLong("timestamp") ?: 0L
            val format = message.jsonOptString("format") ?: "jpeg"
            val width = message.jsonOptInt("width") ?: 640
            val height = message.jsonOptInt("height") ?: 480
            val base64Data = message.jsonOptString("data") ?: return@runCatching

            message.jsonOptString("hostAddress")?.let { addr ->
                if (addr.isNotBlank() && _hostAddress.value != addr) _hostAddress.value = addr
            }
            message.jsonOptString("sessionId")?.let { sid ->
                if (sid.isNotBlank() && _sessionId.value != sid) _sessionId.value = sid
            }

            val frameBytes = decodeBase64OrNull(base64Data) ?: run {
                println("$TAG: ⚠️ base64 decode failed id=$id")
                return@runCatching
            }

            _latestVideoFrame.value = VideoFrame(id, timestamp, frameBytes, width, height, format)
        }.onFailure { e ->
            println("$TAG: ❌ handleVideoFrame error: $e")
        }
    }

    private fun handlePaymentRequest(message: String) {
        runCatching {
            val sessionId = message.jsonOptString("id") ?: return@runCatching
            val amount = message.jsonOptString("amount") ?: ""
            val payTo = message.jsonOptString("payTo") ?: ""

            if (_sessionId.value != sessionId) _sessionId.value = sessionId
            if (payTo.isNotBlank() && _hostAddress.value != payTo) _hostAddress.value = payTo

            println("$TAG: 💰 payment request session=$sessionId amount=$amount payTo=$payTo — X402 not yet supported on iOS")
        }.onFailure { e ->
            println("$TAG: ❌ handlePaymentRequest error: $e")
        }
    }

    private fun startConnectionTypePolling() {
        connectionTypePollingJob?.cancel()
        connectionTypePollingJob = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                iosViewerDetectConnectionTypeHandler?.let { notifyConnectionTypeChanged(it()) }
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
        Regex(""""$key"\s*:\s*"([^"]*)"""").find(this)?.groupValues?.getOrNull(1)?.takeIf { it.isNotEmpty() }

    private fun String.jsonOptLong(key: String): Long? =
        Regex(""""$key"\s*:\s*(-?\d+)""").find(this)?.groupValues?.getOrNull(1)?.toLongOrNull()

    private fun String.jsonOptInt(key: String): Int? =
        Regex(""""$key"\s*:\s*(-?\d+)""").find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeBase64OrNull(value: String): ByteArray? =
        runCatching {
            val normalised = value.replace('-', '+').replace('_', '/').trimEnd('=')
            val padded = normalised + "=".repeat((4 - normalised.length % 4) % 4)
            Base64.decode(padded)
        }.getOrNull()
}
