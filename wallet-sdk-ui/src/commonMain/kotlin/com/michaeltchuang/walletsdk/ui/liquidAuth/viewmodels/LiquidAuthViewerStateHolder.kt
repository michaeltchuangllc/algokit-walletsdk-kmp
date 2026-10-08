package com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.BudgetCap
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ChatMessage
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ConsentApproval
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ConsentTerms
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.GatingMode
import com.michaeltchuang.walletsdk.core.railmpp.utils.MppPayments
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.model.IceConnectionType
import com.michaeltchuang.walletsdk.ui.liquidStream.utils.SESSION_LOGGED_OUT
import io.github.aakira.napier.Napier
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.locks.SynchronizedObject
import io.ktor.utils.io.locks.synchronized
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime

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
        if (other == null || this::class != other::class) return false

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

@OptIn(InternalAPI::class)
open class LiquidAuthViewerStateHolder : ViewModel() {
    companion object {
        private const val TAG = "LiquidAuthViewerState"

        // No frames for this long = stream ended. Tune this to trade off responsiveness
        // (lower = viewer disconnects faster after the host stops) vs. tolerance for
        // transient frame gaps/hiccups (higher = fewer false-positive disconnects).
        private const val STREAM_TIMEOUT_MS = 8_000L
        private const val STREAM_REJECTION_COOLDOWN_MS = 10_000L

        private const val BASE58_ALPHABET =
            "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

        /**
         * Decodes a Base58 (Bitcoin alphabet) string without relying on JVM `BigInteger`,
         * so it is available on every platform.
         */
        fun decodeBase58(input: String): ByteArray? {
            if (input.isEmpty()) return ByteArray(0)

            // Big-endian base-256 accumulator built up one base-58 digit at a time.
            val bytes = ArrayList<Int>()
            bytes.add(0)
            for (char in input) {
                val digit = BASE58_ALPHABET.indexOf(char)
                if (digit < 0) return null

                var carry = digit
                for (i in bytes.indices) {
                    carry += bytes[i] * 58
                    bytes[i] = carry and 0xFF
                    carry = carry shr 8
                }
                while (carry > 0) {
                    bytes.add(carry and 0xFF)
                    carry = carry shr 8
                }
            }

            // Account for leading '1's which represent leading zero bytes.
            val leadingZeroCount = input.takeWhile { it == '1' }.length
            val decoded = ByteArray(leadingZeroCount + bytes.size)
            for (i in bytes.indices) {
                decoded[leadingZeroCount + i] = bytes[bytes.size - 1 - i].toByte()
            }
            return decoded
        }
    }

    /** Current epoch time in millis; exposed as open so platforms can override the clock if needed. */
    @OptIn(ExperimentalTime::class)
    protected open fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()

    // --- Session / generic state ---------------------------------------------------------------
    private val _session = MutableStateFlow(SESSION_LOGGED_OUT)
    val session: StateFlow<String> = _session

    private val _authMessage = MutableStateFlow<AuthMessage?>(null)
    val authMessage: StateFlow<AuthMessage?> = _authMessage

    private val _accountBalance = MutableStateFlow<String?>(null)
    val accountBalance: StateFlow<String?> = _accountBalance

    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _accountAddress = MutableStateFlow("")
    val accountAddress: StateFlow<String> = _accountAddress

    private val _viewerAddress = MutableStateFlow("")
    val viewerAddress: StateFlow<String> = _viewerAddress

    private val _hostAddress = MutableStateFlow("")
    val hostAddress: StateFlow<String> = _hostAddress

    private val _connectionType = MutableStateFlow(IceConnectionType.UNKNOWN)
    val connectionType: StateFlow<IceConnectionType> = _connectionType

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages

    // --- Video streaming state -----------------------------------------------------------------
    private val _videoFrame = MutableStateFlow<VideoFrameData?>(null)
    val videoFrame: StateFlow<VideoFrameData?> = _videoFrame

    private val _lastFrameTimestamp = MutableStateFlow(0L)
    val lastFrameTimestamp: StateFlow<Long> = _lastFrameTimestamp

    private val _isStreamActive = MutableStateFlow(false)
    val isStreamActive: StateFlow<Boolean> = _isStreamActive

    private var hasReceivedAtLeastOneFrame = false
    private var hasTimedOutCurrentStream = false

    // --- MPP consent bridge --------------------------------------------------------------------
    private val _pendingMppConsent = MutableStateFlow<ConsentTerms?>(null)
    val pendingMppConsent: StateFlow<ConsentTerms?> = _pendingMppConsent
    val pendingViewerConsent: StateFlow<ConsentTerms?> = _pendingMppConsent

    private val _isViewerPaymentProcessing = MutableStateFlow(false)
    val isViewerPaymentProcessing: StateFlow<Boolean> = _isViewerPaymentProcessing

    private val _viewerSessionVaultMicroUsdc = MutableStateFlow(0L)
    val viewerSessionVaultMicroUsdc: StateFlow<Long> = _viewerSessionVaultMicroUsdc

    private val _viewerProgressBalanceMicroUsdc = MutableStateFlow(0L)
    val viewerProgressBalanceMicroUsdc: StateFlow<Long> = _viewerProgressBalanceMicroUsdc

    private val _viewerVaultSnapshot = MutableStateFlow<MppPayments.SessionProgressSnapshot?>(null)
    val viewerVaultSnapshot: StateFlow<MppPayments.SessionProgressSnapshot?> = _viewerVaultSnapshot

    private val consentLock = SynchronizedObject()
    private var pendingMppConsentContinuation: CompletableDeferred<ConsentApproval>? = null

    // --- Host rejection (e.g. duplicate wallet address) ----------------------------------------
    private val _streamRejectedMessage = MutableStateFlow<String?>(null)

    /** Non-null while the "host rejected this viewer" modal should be shown. */
    val streamRejectedMessage: StateFlow<String?> = _streamRejectedMessage

    // Late duplicate notices from the same (already torn down) host are ignored for a short while.
    private var streamRejectionAcknowledgedAtMs = 0L

    init {
        // Monitor stream activity and disconnect on timeout (no frames for STREAM_TIMEOUT_MS).
        viewModelScope.launch {
            while (true) {
                val lastFrame = _lastFrameTimestamp.value
                val currentlyActive =
                    if (lastFrame == 0L) {
                        false
                    } else {
                        (currentTimeMillis() - lastFrame) < STREAM_TIMEOUT_MS
                    }
                _isStreamActive.value = currentlyActive

                // While the rejection modal is up, teardown waits for the user's acknowledgement.
                val shouldTimeoutDisconnect =
                    hasReceivedAtLeastOneFrame &&
                        !currentlyActive &&
                        !hasTimedOutCurrentStream &&
                        _streamRejectedMessage.value == null

                if (shouldTimeoutDisconnect) {
                    Napier.w(tag = TAG, message = "Stream timeout triggered - disconnecting")
                    hasTimedOutCurrentStream = true
                    clearVideoFrame()
                    setSession(null)
                    _authMessage.value = null
                    val reason =
                        "Stream disconnected because no video frames were received for 8 seconds. " +
                            "Please reconnect to continue watching."
                    _error.value = reason
                    onStreamTimeout(reason)
                }

                delay(200.milliseconds) // Poll frequently relative to STREAM_TIMEOUT_MS for prompt detection
            }
        }
    }

    /**
     * Platform hook invoked when the stream times out. Platforms override this to tear down
     * the underlying connection (e.g. stop the SignalService) and emit user-facing events.
     */
    protected open fun onStreamTimeout(reason: String) {}

    /**
     * The host refused this viewer (for example, the same wallet address is already watching).
     * Shows a blocking modal; the connection is torn down once the user taps OK via
     * [acknowledgeStreamRejected]. Repeated notices for the same session are ignored.
     */
    fun onStreamRejected(
        reason: String,
        message: String?,
    ) {
        if (_streamRejectedMessage.value != null) return
        if (currentTimeMillis() - streamRejectionAcknowledgedAtMs < STREAM_REJECTION_COOLDOWN_MS) return
        Napier.w(tag = TAG, message = "Stream rejected by host: reason=$reason")
        clearViewerConsent()
        _streamRejectedMessage.value =
            message?.takeIf { it.isNotBlank() }
                ?: "The host rejected this connection ($reason)."
    }

    /** User acknowledged the rejection modal: disconnect from the host. */
    fun acknowledgeStreamRejected() {
        val message = _streamRejectedMessage.value ?: return
        streamRejectionAcknowledgedAtMs = currentTimeMillis()
        _streamRejectedMessage.value = null
        hasTimedOutCurrentStream = true
        stopMppPaymentViewer()
        clearVideoFrame()
        setSession(null)
        _authMessage.value = null
        _error.value = message
        onStreamTimeout(message)
    }

    // --- Public setters / helpers --------------------------------------------------------------
    fun setSession(cookie: String?) {
        _session.value = cookie ?: SESSION_LOGGED_OUT
        if (_session.value == SESSION_LOGGED_OUT) {
            clearViewerVaultState()
        }
    }

    fun setViewerAddress(address: String) {
        _viewerAddress.value = address
    }

    fun setHostAddress(address: String) {
        _hostAddress.value = address
    }

    fun buildViewerHelloMessage(publicKeyBase64: String?): String? {
        val viewer = viewerAddress.value
        if (viewer.isBlank()) return null
        val keyField = if (!publicKeyBase64.isNullOrBlank()) """,\"viewerPublicKey\":\"$publicKeyBase64\""" else ""
        return """{"type":"segment:handshake","viewer":"$viewer"$keyField}"""
    }

    fun applyViewerMessageSessionState(
        hostAddress: String?,
        sessionId: String?,
    ) {
        if (!hostAddress.isNullOrBlank()) setHostAddress(hostAddress)
        if (!sessionId.isNullOrBlank()) setSession(sessionId)
    }

    fun setConnectionType(type: IceConnectionType) {
        _connectionType.value = type
    }

    fun clearViewerConnectionState() {
        _viewerAddress.value = ""
        _hostAddress.value = ""
        _connectionType.value = IceConnectionType.UNKNOWN
        setSession(null)
        _chatMessages.value = emptyList()
    }

    fun setMessage(authMessage: AuthMessage?) {
        _authMessage.value = authMessage
    }

    open fun setAccountAddress(address: String) {
        _accountAddress.value = address
    }

    fun setCount(i: Int) {
        _count.value = i
    }

    protected fun setAccountBalance(balance: String?) {
        _accountBalance.value = balance
    }

    fun setError(errorMessage: String?) {
        _error.value = errorMessage
    }

    fun clearError() {
        _error.value = null
    }

    // --- Video frame handling ------------------------------------------------------------------
    fun setVideoFrame(frame: VideoFrameData?) {
        _videoFrame.value = frame
        if (frame != null) {
            val now = currentTimeMillis()
            _lastFrameTimestamp.value = now
            hasReceivedAtLeastOneFrame = true
            hasTimedOutCurrentStream = false
        }
    }

    /**
     * Lightweight heartbeat for native WebRTC media tracks (as opposed to the legacy
     * `liquid:video:frame` JSON-message path handled by [setVideoFrame]).
     *
     * Platforms that render the remote video track natively (e.g. Android's
     * `SurfaceViewRenderer`/`VideoSink`, iOS's `RTCMTLVideoView`) have no reason to decode
     * frame bytes just to keep the stream-timeout watchdog alive — they can call this
     * whenever a frame is actually rendered/delivered to mark the stream as active without
     * the overhead of allocating/storing [VideoFrameData].
     *
     * Once frames stop arriving (e.g. the host stops or disconnects), the existing watchdog
     * started in the class initializer will fire [onStreamTimeout] after [STREAM_TIMEOUT_MS],
     * tearing down the viewer connection automatically.
     */
    fun markStreamFrameReceived() {
        _lastFrameTimestamp.value = currentTimeMillis()
        hasReceivedAtLeastOneFrame = true
        hasTimedOutCurrentStream = false
    }

    /** Returns whether a frame was received within the timeout window. */
    fun isStreamActive(): Boolean {
        val lastFrame = _lastFrameTimestamp.value
        if (lastFrame == 0L) return false
        return (currentTimeMillis() - lastFrame) < STREAM_TIMEOUT_MS
    }

    /** Clears the current video frame when the stream ends or the client disconnects. */
    fun clearVideoFrame() {
        _videoFrame.value = null
        _lastFrameTimestamp.value = 0
        hasReceivedAtLeastOneFrame = false
    }

    // --- MPP consent bridge --------------------------------------------------------------------
    suspend fun requestMppConsentFromUi(terms: ConsentTerms): ConsentApproval =
        withContext(Dispatchers.Main.immediate) {
            Napier.d(
                tag = TAG,
                message =
                    "[VIEWER_MPP_CONSENT_REQUEST] amount=${terms.amount} asset=${terms.asset} " +
                        "network=${terms.network} gating=${terms.gatingMode}",
            )
            val parentJob = currentCoroutineContext()[Job]
            val deferred =
                synchronized(consentLock) {
                    if (pendingMppConsentContinuation != null || _isViewerPaymentProcessing.value) {
                        return@withContext ConsentApproval(approved = false, autoPaySegments = false)
                    }
                    CompletableDeferred<ConsentApproval>(parentJob).also {
                        pendingMppConsentContinuation = it
                        _pendingMppConsent.value = terms
                    }
                }
            try {
                val approval = deferred.await()
                Napier.d(tag = TAG, message = "[VIEWER_MPP_CONSENT_RESOLVED] approved=${approval.approved}")
                approval
            } finally {
                synchronized(consentLock) {
                    if (pendingMppConsentContinuation === deferred) {
                        pendingMppConsentContinuation = null
                        _pendingMppConsent.value = null
                    }
                }
                deferred.cancel()
            }
        }

    fun approveMppConsent(approval: ConsentApproval) {
        synchronized(consentLock) {
            val deferred = pendingMppConsentContinuation ?: return
            if (!deferred.isActive) return
            _pendingMppConsent.value = null
            deferred.complete(approval)
        }
    }

    fun approveViewerConsent(approval: ConsentApproval) {
        synchronized(consentLock) {
            val deferred = pendingMppConsentContinuation ?: return
            if (!deferred.isActive || _isViewerPaymentProcessing.value) return
            if (approval.approved) _isViewerPaymentProcessing.value = true
            _pendingMppConsent.value = null
            if (!deferred.complete(approval) && approval.approved && pendingMppConsentContinuation === deferred) {
                _isViewerPaymentProcessing.value = false
            }
        }
    }

    fun approveFundedViewerConsent(
        existingBalanceMicroUsdc: Long,
        asset: String = "USDC",
    ) {
        approveMppConsent(
            ConsentApproval(
                approved = true,
                autoPaySegments = true,
                budgetCap =
                    BudgetCap(
                        amount = existingBalanceMicroUsdc.toString(),
                        asset = asset,
                    ),
            ),
        )
    }

    open fun rejectMppConsent() {
        synchronized(consentLock) {
            val deferred = pendingMppConsentContinuation ?: return
            if (!deferred.isActive || _isViewerPaymentProcessing.value) return
            Napier.d(tag = TAG, message = "[VIEWER_MPP_CONSENT_REJECTED]")
            _pendingMppConsent.value = null
            deferred.complete(
                ConsentApproval(
                    approved = false,
                    autoPaySegments = false,
                ),
            )
        }
    }

    open fun rejectViewerConsent() {
        rejectMppConsent()
        stopMppPaymentViewer()
        clearViewerVaultState()
        onStreamTimeout("Stream closed by viewer")
    }

    open fun stopMppPaymentViewer() {}

    fun setViewerPaymentProcessing(isProcessing: Boolean) {
        synchronized(consentLock) {
            _isViewerPaymentProcessing.value = isProcessing
        }
    }

    fun showPendingViewerConsent(terms: ConsentTerms) {
        synchronized(consentLock) {
            if (pendingMppConsentContinuation != null || _isViewerPaymentProcessing.value || _pendingMppConsent.value != null) return
            _pendingMppConsent.value = terms
        }
    }

    fun showPendingViewerConsent(
        amount: String,
        asset: String,
        payTo: String,
        network: String = "algorand-testnet",
        segmentDuration: Int = 3,
        gatingMode: GatingMode = GatingMode.PARTIAL_TIME,
    ) {
        showPendingViewerConsent(
            ConsentTerms(
                gatingMode = gatingMode,
                amount = amount.ifBlank { "1000000" },
                asset = asset,
                network = network,
                segmentDuration = segmentDuration,
            ),
        )
    }

    fun clearViewerConsent() {
        synchronized(consentLock) {
            val deferred = pendingMppConsentContinuation
            pendingMppConsentContinuation = null
            _pendingMppConsent.value = null
            _isViewerPaymentProcessing.value = false
            deferred?.cancel()
        }
    }

    fun setViewerVaultSnapshot(snapshot: MppPayments.SessionProgressSnapshot) {
        _viewerVaultSnapshot.value = snapshot
        _viewerSessionVaultMicroUsdc.value = snapshot.remainingSettledMicroUsdc.coerceAtLeast(0L)
        _viewerProgressBalanceMicroUsdc.value = snapshot.progressBalanceMicroUsdc.coerceAtLeast(0L)
    }

    private fun clearViewerVaultState() {
        _viewerVaultSnapshot.value = null
        _viewerSessionVaultMicroUsdc.value = 0L
        _viewerProgressBalanceMicroUsdc.value = 0L
    }

    fun setViewerSessionVaultProgress(
        remainingBalanceMicroUsdc: Long,
        progressBalanceMicroUsdc: Long,
    ) {
        // Legacy callbacks lack the full confirmed vault state and must not overwrite it.
        if (_viewerVaultSnapshot.value != null) return
        _viewerSessionVaultMicroUsdc.value = remainingBalanceMicroUsdc.coerceAtLeast(0L)
        _viewerProgressBalanceMicroUsdc.value = progressBalanceMicroUsdc.coerceAtLeast(0L)
    }

    fun setViewerSessionVaultBalance(balanceMicroUsdc: Long) {
        setViewerSessionVaultProgress(
            remainingBalanceMicroUsdc = balanceMicroUsdc,
            progressBalanceMicroUsdc = balanceMicroUsdc,
        )
    }

    fun addChatMessage(message: ChatMessage) {
        _chatMessages.value = _chatMessages.value + message
    }
}
