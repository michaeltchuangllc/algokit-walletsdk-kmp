package com.michaeltchuang.walletsdk.ui.liquidStream.domain.transport

import com.michaeltchuang.walletsdk.core.railmpp.core.RtcRtpSender
import com.michaeltchuang.walletsdk.ui.liquidAuth.service.iosBroadcastGateVideoHandler

actual class BroadcastRtcRtpSender actual constructor() : RtcRtpSender {
    actual override fun setTrackEnabled(enabled: Boolean) {
        val handler = iosBroadcastGateVideoHandler
        if (handler != null) {
            handler(enabled)
            println("IOSBroadcastRtcRtpSender: setTrackEnabled($enabled)")
        } else {
            println("IOSBroadcastRtcRtpSender: setTrackEnabled($enabled) skipped — iosBroadcastGateVideoHandler not set")
        }
    }
}
