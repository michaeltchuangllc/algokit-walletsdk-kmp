package com.michaeltchuang.walletsdk.ui.liquidStream

import com.michaeltchuang.walletsdk.core.railmpp.MppClientConfig
import com.michaeltchuang.walletsdk.core.railmpp.MppPaymentRail
import com.michaeltchuang.walletsdk.core.railmpp.core.ClientConfig
import com.michaeltchuang.walletsdk.core.railmpp.core.ConsentApproval
import com.michaeltchuang.walletsdk.core.railmpp.core.ConsentHandler
import com.michaeltchuang.walletsdk.core.railmpp.core.ConsentTerms
import com.michaeltchuang.walletsdk.core.railmpp.core.PaymentReceipt
import com.michaeltchuang.walletsdk.core.railmpp.core.PaymentRequest
import com.michaeltchuang.walletsdk.core.railmpp.core.PaywalledRTCClient
import com.michaeltchuang.walletsdk.core.railmpp.core.RailPayment
import com.michaeltchuang.walletsdk.core.railmpp.core.SpendSummary

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
        set(v) {
            rtcClient.onPaymentRequested = v
        }

    /** Called when the viewer has submitted the signed MPP credential (`segment:payment`). */
    var onPaymentSubmitted: ((RailPayment) -> Unit)?
        get() = rtcClient.onPaymentSubmitted
        set(v) {
            rtcClient.onPaymentSubmitted = v
        }

    /** Called when the host acknowledges the payment (receipt received). */
    var onPaymentAccepted: ((PaymentReceipt) -> Unit)?
        get() = rtcClient.onPaymentReceipt
        set(v) {
            rtcClient.onPaymentReceipt = v
        }

    /** Called when consent is requested by the payment framework before the first segment. */
    var onConsentRequested: ((ConsentTerms) -> Unit)?
        get() = rtcClient.onConsentRequested
        set(v) {
            rtcClient.onConsentRequested = v
        }

    /** Called when the user approves the consent dialog. */
    var onConsentApproved: ((ConsentApproval) -> Unit)?
        get() = rtcClient.onConsentApproved
        set(v) {
            rtcClient.onConsentApproved = v
        }

    /** Called when the user denies the consent dialog. */
    var onConsentDenied: (() -> Unit)?
        get() = rtcClient.onConsentDenied
        set(v) {
            rtcClient.onConsentDenied = v
        }

    /** Called when the DataChannel transitions to open. */
    var onDataChannelOpen: (() -> Unit)?
        get() = rtcClient.onDataChannelOpen
        set(v) {
            rtcClient.onDataChannelOpen = v
        }

    /** Called when the stream starts (first segment approved). */
    var onStreamStarted: (() -> Unit)?
        get() = rtcClient.onStreamStarted
        set(v) {
            rtcClient.onStreamStarted = v
        }

    /** Called when the stream is gated (budget exceeded or segment boundary). */
    var onStreamGated: ((reason: String) -> Unit)?
        get() = rtcClient.onStreamGated
        set(v) {
            rtcClient.onStreamGated = v
        }

    /** Called when the stream resumes after gating. */
    var onStreamResumed: (() -> Unit)?
        get() = rtcClient.onStreamResumed
        set(v) {
            rtcClient.onStreamResumed = v
        }

    /** Called when the viewer's budget cap is exceeded. */
    var onBudgetExceeded: ((SpendSummary) -> Unit)?
        get() = rtcClient.onBudgetExceeded
        set(v) {
            rtcClient.onBudgetExceeded = v
        }

    /** Called when the session terminates. */
    var onSessionTerminated: (() -> Unit)?
        get() = rtcClient.onSessionTerminated
        set(v) {
            rtcClient.onSessionTerminated = v
        }

    /** Called on internal errors. */
    var onError: ((Throwable) -> Unit)?
        get() = rtcClient.onError
        set(v) {
            rtcClient.onError = v
        }

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
