package com.michaeltchuang.walletsdk.ui.liquidStream.domain.transport

import com.michaeltchuang.walletsdk.core.railmpp.core.RtcRtpSender

actual class BroadcastRtcRtpSender actual constructor() : RtcRtpSender {
    actual override fun setTrackEnabled(enabled: Boolean) {
        // Android host wiring currently passes concrete WebRtcRtpSender instances when native
        // RtpSender handles are available. This no-op actual exists so common code can refer to
        // the broadcast sender role without forcing Android UI paths to own a placeholder sender.
    }
}
