package com.michaeltchuang.walletsdk.ui.liquidAuth

import androidx.compose.runtime.Composable
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.AuthMessage

@Composable
expect fun connect(authMessage: AuthMessage,algoAddress: String)
