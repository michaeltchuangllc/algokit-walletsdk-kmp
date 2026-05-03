package com.michaeltchuang.walletsdk.ui.liquidAuth.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object AnswerScreenState {
    var isVisible by mutableStateOf(false)
    var accountAddress by mutableStateOf("")
}