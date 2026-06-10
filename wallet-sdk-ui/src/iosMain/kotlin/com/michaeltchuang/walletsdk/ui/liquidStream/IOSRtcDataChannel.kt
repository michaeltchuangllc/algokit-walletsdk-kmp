package com.michaeltchuang.walletsdk.ui.liquidStream

import com.michaeltchuang.walletsdk.core.railmpp.core.RtcDataChannel
import com.michaeltchuang.walletsdk.core.railmpp.core.RtcDataChannelObserver
import com.michaeltchuang.walletsdk.core.railmpp.core.RtcDataChannelState

class IOSRtcDataChannel(
    private val sendMessageProvider: () -> ((message: String) -> Unit)? = { iosViewerSendMessageHandler },
) : RtcDataChannel {
    private var observer: RtcDataChannelObserver? = null
    private var currentState: RtcDataChannelState = RtcDataChannelState.CONNECTING

    // ── RtcDataChannel impl ──────────────────────────────────────────────────

    override fun state(): RtcDataChannelState = currentState

    override fun send(bytes: ByteArray) {
        val handler = sendMessageProvider()
        if (handler != null) {
            handler(bytes.decodeToString())
        } else {
            println("IOSRtcDataChannel: ⚠️ send skipped — sendMessageProvider returned null")
        }
    }

    override fun registerObserver(observer: RtcDataChannelObserver) {
        this.observer = observer
        // If the channel was already opened before the observer was attached, fire immediately.
        if (currentState == RtcDataChannelState.OPEN) {
            observer.onStateChange()
        }
    }

    override fun close() {
        if (currentState != RtcDataChannelState.CLOSED) {
            currentState = RtcDataChannelState.CLOSED
            observer?.onStateChange()
        }
    }

    // ── Lifecycle / inbound notifications ────────────────────────────────────

    /** Transitions the channel to OPEN and notifies the registered observer. Idempotent. */
    fun notifyOpen() {
        if (currentState != RtcDataChannelState.OPEN) {
            currentState = RtcDataChannelState.OPEN
            observer?.onStateChange()
        }
    }

    /** Delivers an inbound DC message to the registered observer. */
    fun notifyMessage(message: String) {
        observer?.onMessage(message.encodeToByteArray())
    }

    /** Transitions the channel to CLOSED and notifies the registered observer. */
    fun notifyClosed() {
        if (currentState != RtcDataChannelState.CLOSED) {
            currentState = RtcDataChannelState.CLOSED
            observer?.onStateChange()
        }
    }
}
