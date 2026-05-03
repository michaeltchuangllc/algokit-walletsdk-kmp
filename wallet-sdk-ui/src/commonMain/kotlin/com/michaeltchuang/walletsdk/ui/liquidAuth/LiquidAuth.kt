package com.michaeltchuang.walletsdk.ui.liquidAuth

import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.AuthMessage

expect fun connectLiquidAuth(
    authMessage: AuthMessage,
    accountAddress: String,
)
