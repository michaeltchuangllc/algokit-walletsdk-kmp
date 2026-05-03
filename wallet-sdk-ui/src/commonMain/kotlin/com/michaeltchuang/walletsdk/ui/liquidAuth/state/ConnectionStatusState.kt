package com.michaeltchuang.walletsdk.ui.liquidAuth.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Global state that exposes Liquid Auth connection details for UI consumption
 * outside the AnswerScreen overlay (e.g. in the demo app's navigation bar).
 */
object ConnectionStatusState {
    var isVisible by mutableStateOf(false)
    var isExpanded by mutableStateOf(false)
    var session by mutableStateOf("")
    var origin by mutableStateOf("")
    var requestId by mutableStateOf("")
    var accountAddress by mutableStateOf("")
    var onDisconnect: (() -> Unit)? = null
}