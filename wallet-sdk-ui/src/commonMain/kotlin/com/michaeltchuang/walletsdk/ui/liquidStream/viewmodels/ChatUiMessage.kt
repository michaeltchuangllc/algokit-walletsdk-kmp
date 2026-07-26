package com.michaeltchuang.walletsdk.ui.liquidStream.viewmodels

data class ChatUiMessage(
    val sender: String,
    val text: String,
    val timestamp: Long,
    val amount: String? = null,
    val asset: String? = null,
)
