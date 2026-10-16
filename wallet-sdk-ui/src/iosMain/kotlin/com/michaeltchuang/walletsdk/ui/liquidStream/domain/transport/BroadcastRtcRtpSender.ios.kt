package com.michaeltchuang.walletsdk.ui.liquidStream.domain.transport

import com.michaeltchuang.walletsdk.core.railmpp.core.RtcRtpSender
import com.michaeltchuang.walletsdk.ui.liquidAuth.service.iosBroadcastGateVideoHandler
import io.github.aakira.napier.Napier

actual class BroadcastRtcRtpSender actual constructor() : RtcRtpSender {
    actual override fun setTrackEnabled(enabled: Boolean) {
        val handler = iosBroadcastGateVideoHandler
        if (handler != null) {
            handler(enabled)
            Napier.d("IOSBroadcastRtcRtpSender: setTrackEnabled($enabled)")
        } else {
            Napier.d("IOSBroadcastRtcRtpSender: setTrackEnabled($enabled) skipped — iosBroadcastGateVideoHandler not set")
        }
    }
}
