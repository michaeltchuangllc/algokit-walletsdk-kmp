package com.michaeltchuang.walletsdk.ui.liquidStream.domain.transport

import com.michaeltchuang.walletsdk.core.railmpp.core.RtcRtpSender

class CallbackRtcRtpSender(
    private val setTrackEnabledHandlerProvider: () -> ((enabled: Boolean) -> Unit)?,
    private val logTag: String = "CallbackRtcRtpSender",
) : RtcRtpSender {
    override fun setTrackEnabled(enabled: Boolean) {
        val handler = setTrackEnabledHandlerProvider()
        if (handler != null) {
            handler(enabled)
            println("$logTag: setTrackEnabled($enabled)")
        } else {
            println("$logTag: setTrackEnabled($enabled) skipped — handlerProvider returned null")
        }
    }
}
