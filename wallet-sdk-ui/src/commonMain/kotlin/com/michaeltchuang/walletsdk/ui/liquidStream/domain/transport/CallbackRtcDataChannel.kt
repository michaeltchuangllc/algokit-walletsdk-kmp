package com.michaeltchuang.walletsdk.ui.liquidStream.domain.transport

import com.michaeltchuang.walletsdk.core.railmpp.core.RtcDataChannel
import com.michaeltchuang.walletsdk.core.railmpp.core.RtcDataChannelObserver
import com.michaeltchuang.walletsdk.core.railmpp.core.RtcDataChannelState

open class CallbackRtcDataChannel(
    private val sendMessageProvider: () -> ((message: String) -> Unit)?,
    private val logTag: String = "CallbackRtcDataChannel",
) : RtcDataChannel {
    private var observer: RtcDataChannelObserver? = null
    private var currentState: RtcDataChannelState = RtcDataChannelState.CONNECTING

    override fun state(): RtcDataChannelState = currentState

    override fun send(bytes: ByteArray) {
        val handler = sendMessageProvider()
        if (handler != null) {
            handler(bytes.decodeToString())
        } else {
            println("$logTag: send skipped — sendMessageProvider returned null")
        }
    }

    override fun registerObserver(observer: RtcDataChannelObserver) {
        this.observer = observer
        if (currentState == RtcDataChannelState.OPEN) {
            observer.onStateChange()
        }
    }

    override fun close() {
        notifyClosed()
    }

    fun notifyOpen() {
        if (currentState != RtcDataChannelState.OPEN) {
            currentState = RtcDataChannelState.OPEN
            observer?.onStateChange()
        }
    }

    fun notifyMessage(message: String) {
        observer?.onMessage(message.encodeToByteArray())
    }

    fun notifyClosed() {
        if (currentState != RtcDataChannelState.CLOSED) {
            currentState = RtcDataChannelState.CLOSED
            observer?.onStateChange()
        }
    }
}
