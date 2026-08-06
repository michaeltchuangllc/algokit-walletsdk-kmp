package com.michaeltchuang.walletsdk.core.railmpp.domain.model

data class SessionStats(
    val sessionId: String,
    var segmentsDelivered: Int = 0,
    var segmentsPaid: Int = 0,
    var totalAmountReceived: String = "0",
    var totalBytesTransferred: Long = 0,
    var durationSeconds: Long = 0,
)
