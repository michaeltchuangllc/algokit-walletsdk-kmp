package com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels

sealed interface DataChannelRequest {
    data class SignTransactions(
        val pendingRequest: PendingSignTransactionRequest,
    ) : DataChannelRequest

    data class VideoFrame(
        val payload: String,
    ) : DataChannelRequest
}

data class PendingSignTransactionRequest(
    val params: Any,
    val message: Any,
)
