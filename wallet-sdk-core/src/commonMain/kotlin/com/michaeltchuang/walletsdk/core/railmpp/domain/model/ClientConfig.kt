package com.michaeltchuang.walletsdk.core.railmpp.domain.model

data class ClientConfig(
    val autoPaySegments: Boolean = false,
    val maxAutoPaySegments: Int? = null,
    val budgetCap: BudgetCap? = null,
    val expectedNetwork: String? = null,
    val expectedViewerAddress: String? = null,
)
