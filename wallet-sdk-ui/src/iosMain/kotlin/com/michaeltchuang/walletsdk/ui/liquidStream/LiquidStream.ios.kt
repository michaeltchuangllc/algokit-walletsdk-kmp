package com.michaeltchuang.walletsdk.ui.liquidStream

import com.michaeltchuang.walletsdk.core.railmpp.MppClientConfig
import com.michaeltchuang.walletsdk.core.railmpp.MppPaymentRail
import com.michaeltchuang.walletsdk.core.railmpp.MppServerConfig
import com.michaeltchuang.walletsdk.core.railmpp.core.ClientConfig
import com.michaeltchuang.walletsdk.core.railmpp.core.ConsentApproval
import com.michaeltchuang.walletsdk.core.railmpp.core.ConsentHandler
import com.michaeltchuang.walletsdk.core.railmpp.core.ConsentTerms
import com.michaeltchuang.walletsdk.core.railmpp.core.GatingConfig
import com.michaeltchuang.walletsdk.core.railmpp.core.InMemoryNonceStore
import com.michaeltchuang.walletsdk.core.railmpp.core.PaywalledRTCClient
import com.michaeltchuang.walletsdk.core.railmpp.core.PaywalledRTCServer
import com.michaeltchuang.walletsdk.core.railmpp.core.PaymentReceipt
import com.michaeltchuang.walletsdk.core.railmpp.core.PaymentRequest
import com.michaeltchuang.walletsdk.core.railmpp.core.RailPayment
import com.michaeltchuang.walletsdk.core.railmpp.core.ServerConfig
import com.michaeltchuang.walletsdk.core.railmpp.core.SpendSummary
import com.michaeltchuang.walletsdk.core.railmpp.data.repository.IosSessionVaultBalanceRepository
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecases.GetRemainingSessionVaultBalanceUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.service.iosBroadcastPaymentDCSendMessageHandler
import com.michaeltchuang.walletsdk.ui.liquidAuth.service.iosBroadcastSendMessageHandler


// ─── Creator (Host / Server side) ────────────────────────────────────────────
@Suppress("unused")
class IOSLiquidStreamCreator(
    mppServerConfig: MppServerConfig,
    serverConfig: ServerConfig,
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
            getRemainingSessionVaultBalanceUseCase =
                GetRemainingSessionVaultBalanceUseCase(IosSessionVaultBalanceRepository()),
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
        set(v) { rtcServer.onSessionStarted = v }

    /** Called when the server has sent a `segment:request` to the viewer. */
    var onPaymentRequested: ((PaymentRequest) -> Unit)?
        get() = rtcServer.onPaymentRequested
        set(v) { rtcServer.onPaymentRequested = v }

    /** Called when the viewer's `segment:payment` has been verified on-chain. */
    var onPaymentSettled: ((PaymentReceipt) -> Unit)?
        get() = rtcServer.onPaymentSettled
        set(v) { rtcServer.onPaymentSettled = v }

    /** Called when the viewer's payment is rejected (bad signature, wrong amount, etc.). */
    var onPaymentRejected: ((reason: String) -> Unit)?
        get() = rtcServer.onPaymentRejected
        set(v) { rtcServer.onPaymentRejected = v }

    /** Called at the start of each new segment. */
    var onSegmentStarted: ((segmentIndex: Int) -> Unit)?
        get() = rtcServer.onSegmentStarted
        set(v) { rtcServer.onSegmentStarted = v }

    /** Called when the stream is gated pending payment for the new segment. */
    var onSegmentGated: ((segmentIndex: Int) -> Unit)?
        get() = rtcServer.onSegmentGated
        set(v) { rtcServer.onSegmentGated = v }

    /** Called when the stream is resumed after a successful payment. */
    var onSegmentResumed: ((segmentIndex: Int) -> Unit)?
        get() = rtcServer.onSegmentResumed
        set(v) { rtcServer.onSegmentResumed = v }

    /** Called when the session ends (viewer disconnected or [terminate] called). */
    var onSessionTerminated: ((sessionId: String) -> Unit)?
        get() = rtcServer.onSessionTerminated
        set(v) { rtcServer.onSessionTerminated = v }

    /** Called on internal errors. */
    var onError: ((Throwable) -> Unit)?
        get() = rtcServer.onError
        set(v) { rtcServer.onError = v }

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

// ─── Viewer (Consumer / Client side) ─────────────────────────────────────────
@Suppress("unused")
class IOSLiquidStreamViewer(
    mppClientConfig: MppClientConfig,
    consentHandler: ConsentHandler,
    clientConfig: ClientConfig = ClientConfig(),
) {
    // ── Core objects ─────────────────────────────────────────────────────────

    /** Consumer-side MPP payment rail. */
    val paymentRail: MppPaymentRail = MppPaymentRail(clientConfig = mppClientConfig)

    /**
     * iOS DataChannel bridge for the VIEWER side.
     */
    val dcChannel: IosRtcDataChannel =
        IosRtcDataChannel(sendMessageProvider = {
            iosViewerPaymentDCSendMessageHandler ?: iosViewerSendMessageHandler
        })

    /** The underlying `PaywalledRTCClient` — access for advanced configuration. */
    val rtcClient: PaywalledRTCClient =
        PaywalledRTCClient(
            paymentRail = paymentRail,
            consent = consentHandler,
            config = clientConfig,
        )

    // ── Forwarded client callbacks ────────────────────────────────────────────

    /** Called when the host sends a `segment:request`. */
    var onPaymentRequested: ((PaymentRequest) -> Unit)?
        get() = rtcClient.onPaymentRequested
        set(v) { rtcClient.onPaymentRequested = v }

    /** Called when the viewer has submitted the signed MPP credential (`segment:payment`). */
    var onPaymentSubmitted: ((RailPayment) -> Unit)?
        get() = rtcClient.onPaymentSubmitted
        set(v) { rtcClient.onPaymentSubmitted = v }

    /** Called when the host acknowledges the payment (receipt received). */
    var onPaymentAccepted: ((PaymentReceipt) -> Unit)?
        get() = rtcClient.onPaymentReceipt
        set(v) { rtcClient.onPaymentReceipt = v }

    /** Called when consent is requested by the payment framework before the first segment. */
    var onConsentRequested: ((ConsentTerms) -> Unit)?
        get() = rtcClient.onConsentRequested
        set(v) { rtcClient.onConsentRequested = v }

    /** Called when the user approves the consent dialog. */
    var onConsentApproved: ((ConsentApproval) -> Unit)?
        get() = rtcClient.onConsentApproved
        set(v) { rtcClient.onConsentApproved = v }

    /** Called when the user denies the consent dialog. */
    var onConsentDenied: (() -> Unit)?
        get() = rtcClient.onConsentDenied
        set(v) { rtcClient.onConsentDenied = v }

    /** Called when the DataChannel transitions to open. */
    var onDataChannelOpen: (() -> Unit)?
        get() = rtcClient.onDataChannelOpen
        set(v) { rtcClient.onDataChannelOpen = v }

    /** Called when the stream starts (first segment approved). */
    var onStreamStarted: (() -> Unit)?
        get() = rtcClient.onStreamStarted
        set(v) { rtcClient.onStreamStarted = v }

    /** Called when the stream is gated (budget exceeded or segment boundary). */
    var onStreamGated: ((reason: String) -> Unit)?
        get() = rtcClient.onStreamGated
        set(v) { rtcClient.onStreamGated = v }

    /** Called when the stream resumes after gating. */
    var onStreamResumed: (() -> Unit)?
        get() = rtcClient.onStreamResumed
        set(v) { rtcClient.onStreamResumed = v }

    /** Called when the viewer's budget cap is exceeded. */
    var onBudgetExceeded: ((SpendSummary) -> Unit)?
        get() = rtcClient.onBudgetExceeded
        set(v) { rtcClient.onBudgetExceeded = v }

    /** Called when the session terminates. */
    var onSessionTerminated: (() -> Unit)?
        get() = rtcClient.onSessionTerminated
        set(v) { rtcClient.onSessionTerminated = v }

    /** Called on internal errors. */
    var onError: ((Throwable) -> Unit)?
        get() = rtcClient.onError
        set(v) { rtcClient.onError = v }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Current spend summary (segments paid, total micro-USDC spent). */
    val spend: SpendSummary get() = rtcClient.spend

    /**
     * Connects [PaywalledRTCClient] to [dcChannel].
     * Call once before [notifyHostConnected].
     */
    fun start() {
        rtcClient.connect(dcChannel)
    }

    /**
     * Signals that the host's RTCDataChannel has transitioned to `.open`.
     * [PaywalledRTCClient] will wait for the first `segment:request`.
     */
    fun notifyHostConnected() {
        dcChannel.notifyOpen()
    }

    /**
     * Forwards an inbound DataChannel message from Swift to [PaywalledRTCClient].
     * Call on every `dataChannel(_:didReceiveMessageWith:)` delegate callback.
     */
    fun notifyMessageReceived(message: String) {
        dcChannel.notifyMessage(message)
    }

    /**
     * Signals that the host's RTCDataChannel has closed.
     */
    fun notifyHostDisconnected() {
        dcChannel.notifyClosed()
    }

    /** Terminates the payment session and closes the DataChannel bridge. */
    fun terminate() {
        rtcClient.terminate()
        dcChannel.notifyClosed()
    }

    /**
     * Enables or disables automatic per-segment payments without showing a consent dialog.
     * Mirrors Android's `LiquidStreamViewer.setAutoPay`.
     */
    fun setAutoPay(enabled: Boolean) {
        rtcClient.setAutoPay(enabled)
    }
}

/** Backward-compatible alias, matching the Android `LiquidSreamViewer` typo alias. */
@Suppress("unused")
typealias IOSLiquidSreamViewer = IOSLiquidStreamViewer
