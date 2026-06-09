package com.michaeltchuang.walletsdk.ui.liquidStream

import com.michaeltchuang.walletsdk.core.railmpp.core.RtcRtpSender

/**
 * iOS implementation of [RtcRtpSender].
 *
 * `PaywalledRTCServer` calls `setTrackEnabled` to gate (pause) or ungate (resume) the host
 * video track based on payment verification.
 *
 * Swift must set [iosBroadcastGateVideoHandler] to receive these callbacks and act on the
 * underlying `RTCVideoTrack.isEnabled` property.
 *
 * Usage from Swift:
 * ```swift
 * IOSRtcRtpSenderKt.iosBroadcastGateVideoHandler = { enabled in
 *     self.localVideoTrack?.isEnabled = enabled
 * }
 * ```
 */
var iosBroadcastGateVideoHandler: ((enabled: Boolean) -> Unit)? = null

class IOSRtcRtpSender : RtcRtpSender {
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
