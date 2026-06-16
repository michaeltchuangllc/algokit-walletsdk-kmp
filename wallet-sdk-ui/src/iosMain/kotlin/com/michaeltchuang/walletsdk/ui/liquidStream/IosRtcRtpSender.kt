package com.michaeltchuang.walletsdk.ui.liquidStream

import com.michaeltchuang.walletsdk.core.railmpp.core.RtcRtpSender

var iosBroadcastGateVideoHandler: ((enabled: Boolean) -> Unit)? = null

class IosRtcRtpSender : RtcRtpSender {
    override fun setTrackEnabled(enabled: Boolean) {
        val handler = iosBroadcastGateVideoHandler
        if (handler != null) {
            handler(enabled)
            println("IOSRtcRtpSender: 🎛️ setTrackEnabled($enabled)")
        } else {
            println("IOSRtcRtpSender: ⚠️ setTrackEnabled($enabled) skipped — iosBroadcastGateVideoHandler not set")
        }
    }
}
