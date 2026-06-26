package com.michaeltchuang.walletsdk.ui.liquidStream

import com.michaeltchuang.walletsdk.core.railmpp.MppPaymentRail
import com.michaeltchuang.walletsdk.core.railmpp.MppServerConfig
import com.michaeltchuang.walletsdk.core.railmpp.core.GatingConfig
import com.michaeltchuang.walletsdk.core.railmpp.core.InMemoryNonceStore
import com.michaeltchuang.walletsdk.core.railmpp.core.PaymentReceipt
import com.michaeltchuang.walletsdk.core.railmpp.core.PaymentRequest
import com.michaeltchuang.walletsdk.core.railmpp.core.PaywalledRTCServer
import com.michaeltchuang.walletsdk.core.railmpp.core.ServerConfig
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetRemainingSessionVaultBalanceUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.service.iosBroadcastPaymentDCSendMessageHandler
import com.michaeltchuang.walletsdk.ui.liquidAuth.service.iosBroadcastSendMessageHandler

// ─── Creator (Host / Server side) ────────────────────────────────────────────
@Suppress("unused")
class IOSLiquidStreamCreator(
    mppServerConfig: MppServerConfig,
    serverConfig: ServerConfig,
    private val getRemainingSessionVaultBalanceUseCase: GetRemainingSessionVaultBalanceUseCase,
) {
    // ── Core objects ─────────────────────────────────────────────────────────

    /** Provider-side MPP payment rail. */
    val paymentRail: MppPaymentRail = MppPaymentRail(serverConfig = mppServerConfig)

    /**
     * iOS DataChannel bridge for the HOST side.
     * Outbound messages are forwarded to [iosBroadcastPaymentDCSendMessageHandler]
     * (the dedicated "x402-payment-channel" DC), falling back to the general
     * [iosBroadcastSendMessageHandler] if the payment DC is not yet available.
     * Video frames are sent separately via [iosBroadcastSendMessageHandler] and
     * are NOT routed through this channel.
     */
    val dcChannel: IosRtcDataChannel =
        IosRtcDataChannel(sendMessageProvider = {
            iosBroadcastPaymentDCSendMessageHandler ?: iosBroadcastSendMessageHandler
        })

    /** The underlying `PaywalledRTCServer` — access for advanced configuration. */
    val rtcServer: PaywalledRTCServer =
        PaywalledRTCServer(
            paymentRail = paymentRail,
            config = serverConfig,
            getRemainingSessionVaultBalanceUseCase = getRemainingSessionVaultBalanceUseCase,
            nonceStore = InMemoryNonceStore(),
        )

    init {
        require(serverConfig.gating.payTo == mppServerConfig.recipient) {
            "IOSLiquidStreamCreator: gating.payTo (${serverConfig.gating.payTo}) " +
                "must match MppServerConfig.recipient (${mppServerConfig.recipient})"
        }
    }

    // ── Forwarded server callbacks ────────────────────────────────────────────

    /** Called when the session starts. Set before [start]. */
    var onSessionStarted: ((sessionId: String) -> Unit)?
        get() = rtcServer.onSessionStarted
        set(v) {
            rtcServer.onSessionStarted = v
        }

    /** Called when the server has sent a `segment:request` to the viewer. */
    var onPaymentRequested: ((PaymentRequest) -> Unit)?
        get() = rtcServer.onPaymentRequested
        set(v) {
            rtcServer.onPaymentRequested = v
        }

    /** Called when the viewer's `segment:payment` has been verified on-chain. */
    var onPaymentSettled: ((PaymentReceipt) -> Unit)?
        get() = rtcServer.onPaymentSettled
        set(v) {
            rtcServer.onPaymentSettled = v
        }

    /** Called when the viewer's payment is rejected (bad signature, wrong amount, etc.). */
    var onPaymentRejected: ((reason: String) -> Unit)?
        get() = rtcServer.onPaymentRejected
        set(v) {
            rtcServer.onPaymentRejected = v
        }

    /** Called at the start of each new segment. */
    var onSegmentStarted: ((segmentIndex: Int) -> Unit)?
        get() = rtcServer.onSegmentStarted
        set(v) {
            rtcServer.onSegmentStarted = v
        }

    /** Called when the stream is gated pending payment for the new segment. */
    var onSegmentGated: ((segmentIndex: Int) -> Unit)?
        get() = rtcServer.onSegmentGated
        set(v) {
            rtcServer.onSegmentGated = v
        }

    /** Called when the stream is resumed after a successful payment. */
    var onSegmentResumed: ((segmentIndex: Int) -> Unit)?
        get() = rtcServer.onSegmentResumed
        set(v) {
            rtcServer.onSegmentResumed = v
        }

    /** Called when the session ends (viewer disconnected or [terminate] called). */
    var onSessionTerminated: ((sessionId: String) -> Unit)?
        get() = rtcServer.onSessionTerminated
        set(v) {
            rtcServer.onSessionTerminated = v
        }

    /** Called on internal errors. */
    var onError: ((Throwable) -> Unit)?
        get() = rtcServer.onError
        set(v) {
            rtcServer.onError = v
        }

    // ── Public API ────────────────────────────────────────────────────────────

    /** The session ID generated by [PaywalledRTCServer]. Available after construction. */
    val sessionId: String get() = rtcServer.sessionId

    /**
     * Starts the paywalled server: registers [IosRtcRtpSender] for track gating and begins
     * listening on [dcChannel].  Call this once before [notifyViewerConnected].
     */
    fun start() {
        rtcServer.listen(dcChannel, listOf(IosRtcRtpSender()))
    }

    /**
     * Signals that the viewer's RTCDataChannel has transitioned to `.open`.
     * [PaywalledRTCServer] will immediately send `{"type":"segment:request",...}`.
     */
    fun notifyViewerConnected() {
        dcChannel.notifyOpen()
    }

    /**
     * Forwards an inbound DataChannel message from Swift to [PaywalledRTCServer].
     * Call on every `dataChannel(_:didReceiveMessageWith:)` delegate callback.
     */
    fun notifyMessageReceived(message: String) {
        dcChannel.notifyMessage(message)
    }

    /**
     * Signals that the viewer's RTCDataChannel has closed.
     * [PaywalledRTCServer] will terminate the active session.
     */
    fun notifyViewerDisconnected() {
        dcChannel.notifyClosed()
    }

    /** Terminates the paywalled session and closes the DataChannel bridge. */
    fun terminate(reason: String? = null) {
        println("IOSLiquidStreamCreator: 🛑 terminate reason=$reason")
        dcChannel.notifyClosed()
    }

    /** Updates the gating config (amount / asset / segment duration) for the next segment. */
    fun updateGating(gating: GatingConfig) {
        rtcServer.updateGating(gating)
    }

    /** Replaces the full server config (viewer address, authorized signer key, etc.). */
    fun updateConfig(config: ServerConfig) {
        rtcServer.updateConfig(config)
    }
}
