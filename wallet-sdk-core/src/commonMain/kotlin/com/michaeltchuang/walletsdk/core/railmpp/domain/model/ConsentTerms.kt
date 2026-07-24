package com.michaeltchuang.walletsdk.core.railmpp.domain.model

data class ConsentTerms(
    val gatingMode: GatingMode,
    val amount: String,
    val asset: String,
    val network: String,
    val segmentDuration: Int? = null,
    val segmentBytes: Long? = null,
    val suggestedBudgetCap: String? = null,
)