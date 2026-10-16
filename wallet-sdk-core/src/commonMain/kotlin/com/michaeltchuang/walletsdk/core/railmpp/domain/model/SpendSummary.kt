package com.michaeltchuang.walletsdk.core.railmpp.domain.model

data class SpendSummary(
    var totalAmount: String = "0",
    var asset: String = "",
    var segmentsPaid: Int = 0,
    val transactions: MutableList<SpendTransaction> = mutableListOf(),
)
