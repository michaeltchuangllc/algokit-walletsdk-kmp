package com.michaeltchuang.walletsdk.ui.liquidAuth

import com.michaeltchuang.walletsdk.ui.liquidAuth.state.AnswerScreenState
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.AuthMessage

object AuthMessageStorage {
    var authMessage = AuthMessage("", "", "")
}

actual fun connectLiquidAuth(
    authMessage: AuthMessage,
    accountAddress: String,
) {
    AuthMessageStorage.authMessage =
        AuthMessage(
            authMessage.origin,
            authMessage.requestId,
            authMessage.appId,
        )
    AnswerScreenState.accountAddress = accountAddress
    AnswerScreenState.isVisible = true
}
