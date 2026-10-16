package com.michaeltchuang.walletsdk.ui.liquidStream.domain.transport

import com.michaeltchuang.walletsdk.core.railmpp.core.RtcRtpSender
import io.github.aakira.napier.Napier

class CallbackRtcRtpSender(
    private val setTrackEnabledHandlerProvider: () -> ((enabled: Boolean) -> Unit)?,
    private val logTag: String = "CallbackRtcRtpSender",
) : RtcRtpSender {
    override fun setTrackEnabled(enabled: Boolean) {
        val handler = setTrackEnabledHandlerProvider()
        if (handler != null) {
            handler(enabled)
            Napier.d("$logTag: setTrackEnabled($enabled)")
        } else {
            Napier.d("$logTag: setTrackEnabled($enabled) skipped — handlerProvider returned null")
        }
    }
}
