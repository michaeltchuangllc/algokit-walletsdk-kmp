package com.michaeltchuang.walletsdk.ui.liquidStream.domain.transport

import com.michaeltchuang.walletsdk.core.railmpp.core.RtcRtpSender

expect class BroadcastRtcRtpSender() : RtcRtpSender {
    override fun setTrackEnabled(enabled: Boolean)
}
