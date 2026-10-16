package com.michaeltchuang.walletsdk.core.railmpp.core

enum class RtcDataChannelState { CONNECTING, OPEN, CLOSING, CLOSED }

/** Observer for DataChannel state and message events (delivered on the native transport thread). */
interface RtcDataChannelObserver {
    fun onStateChange()

    fun onMessage(data: ByteArray)
}

/** Bidirectional payment-signaling channel. */
interface RtcDataChannel {
    fun state(): RtcDataChannelState

    fun send(bytes: ByteArray)

    fun registerObserver(observer: RtcDataChannelObserver)

    fun close()
}

/** Gateable media sender — the provider enables/disables tracks to gate the stream. */
interface RtcRtpSender {
    fun setTrackEnabled(enabled: Boolean)
}
