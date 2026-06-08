package com.michaeltchuang.walletsdk.core.railmpp.core

import org.webrtc.DataChannel
import org.webrtc.RtpSender
import java.nio.ByteBuffer

/** Wraps an org.webrtc [DataChannel] as a platform-agnostic [RtcDataChannel]. */
class WebRtcDataChannel(
    private val dataChannel: DataChannel,
) : RtcDataChannel {
    override fun state(): RtcDataChannelState =
        when (dataChannel.state()) {
            DataChannel.State.CONNECTING -> RtcDataChannelState.CONNECTING
            DataChannel.State.OPEN -> RtcDataChannelState.OPEN
            DataChannel.State.CLOSING -> RtcDataChannelState.CLOSING
            DataChannel.State.CLOSED -> RtcDataChannelState.CLOSED
            null -> RtcDataChannelState.CLOSED
        }

    override fun send(bytes: ByteArray) {
        dataChannel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
    }

    override fun registerObserver(observer: RtcDataChannelObserver) {
        dataChannel.registerObserver(
            object : DataChannel.Observer {
                override fun onBufferedAmountChange(amount: Long) {}

                override fun onStateChange() = observer.onStateChange()

                override fun onMessage(buffer: DataChannel.Buffer?) {
                    val byteBuffer = buffer?.data ?: return
                    val bytes = ByteArray(byteBuffer.remaining())
                    byteBuffer.get(bytes)
                    observer.onMessage(bytes)
                }
            },
        )
    }

    override fun close() = dataChannel.close()
}

/** Wraps an org.webrtc [RtpSender] as a platform-agnostic [RtcRtpSender]. */
class WebRtcRtpSender(
    private val sender: RtpSender,
) : RtcRtpSender {
    override fun setTrackEnabled(enabled: Boolean) {
        sender.track()?.setEnabled(enabled)
    }
}
