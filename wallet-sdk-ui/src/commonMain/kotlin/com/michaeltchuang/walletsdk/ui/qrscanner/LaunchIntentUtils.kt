package com.michaeltchuang.walletsdk.ui.qrscanner

import androidx.compose.runtime.Composable

@Composable
expect fun launchIntentWithUri(uri: String)
