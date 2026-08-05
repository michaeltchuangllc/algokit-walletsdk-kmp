package com.michaeltchuang.walletsdk.core.railmpp.domain.model

data class SpendTransaction(
    val txId: String,
    val amount: String,
    val segmentIndex: Int,
    val timestamp: Long,
)
