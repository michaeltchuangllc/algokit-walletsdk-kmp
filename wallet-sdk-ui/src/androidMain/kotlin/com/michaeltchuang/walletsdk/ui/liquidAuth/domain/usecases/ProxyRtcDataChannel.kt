package com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases

import com.michaeltchuang.walletsdk.core.railmpp.core.RtcDataChannel
import com.michaeltchuang.walletsdk.core.railmpp.core.RtcDataChannelObserver
import com.michaeltchuang.walletsdk.core.railmpp.core.RtcDataChannelState

/**
 * A synthetic [RtcDataChannel] used when the remote host (e.g. an iOS host) sends payment
 * messages on the general "liquid" DataChannel rather than creating a dedicated
 * "x402-payment-channel".
 *
 * - SENDING: forwards bytes to [sendImpl], which sends them on the general DC.
 * - RECEIVING: relies on [injectMessage] being called externally (from the general DC
 *   message handler) rather than registering an observer on a real WebRTC channel.
 * - STATE: always reports OPEN so that `PaywalledRTCClient` fires `onDataChannelOpen`
 *   immediately after `connect()`.
 */
internal class ProxyRtcDataChannel(
    private val sendImpl: (ByteArray) -> Unit,
) : RtcDataChannel {

    private var observer: RtcDataChannelObserver? = null

    override fun state(): RtcDataChannelState = RtcDataChannelState.OPEN

    override fun send(bytes: ByteArray) {
        sendImpl(bytes)
    }

    override fun registerObserver(observer: RtcDataChannelObserver) {
        this.observer = observer
        // Fire onStateChange(OPEN) immediately so PaywalledRTCClient activates.
        observer.onStateChange()
    }

    override fun close() {
        observer = null
    }

    /**
     * Inject a raw message byte array into the observer — called when the general DC handler
     * receives a payment-protocol message (e.g. `{"type":"segment:request",...}`).
     */
    fun injectMessage(bytes: ByteArray) {
        observer?.onMessage(bytes)
    }
}
