package com.michaeltchuang.walletsdk.core.account.domain.model.algorand

import com.michaeltchuang.walletsdk.core.account.domain.model.local.SeedVaultAccount

data class AlgorandSeedVaultSeed(
    val id: String,
    val name: String,
    val accounts: List<SeedVaultAccount>,
)
