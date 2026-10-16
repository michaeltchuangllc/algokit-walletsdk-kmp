package com.michaeltchuang.walletsdk.ui.liquidAuth.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Global state that drives the platform-specific `AnswerScreenOverlay` on both Android and iOS.
 *
 * Set by `connectLiquidAuth` when a Liquid Auth connection is initiated and observed by the
 * platform-specific overlay so it can survive bottom-sheet dismissal and navigation changes.
 */
object AnswerScreenState {
    var isVisible by mutableStateOf(false)
    var accountAddress by mutableStateOf("")

    /** Origin of the connecting dApp. Used by iOS to start the viewer WebRTC connection. */
    var origin by mutableStateOf("")

    /** Request id of the Liquid Auth session. Used by iOS to start the viewer WebRTC connection. */
    var requestId by mutableStateOf("")
}
