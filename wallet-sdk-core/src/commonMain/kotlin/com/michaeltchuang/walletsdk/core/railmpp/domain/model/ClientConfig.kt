package com.michaeltchuang.walletsdk.core.railmpp.domain.model

data class ClientConfig(
    val autoPaySegments: Boolean = false,
    val maxAutoPaySegments: Int? = null,
    val budgetCap: BudgetCap? = null,
)
