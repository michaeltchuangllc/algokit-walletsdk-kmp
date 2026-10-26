package com.michaeltchuang.walletsdk.core.account.domain.usecase.local

import com.michaeltchuang.walletsdk.core.account.domain.model.algorand.AlgorandSeedVaultSeed
import com.michaeltchuang.walletsdk.core.account.domain.model.local.SeedVaultAccount

fun interface GetAlgorandSeedVaultSeeds {
    suspend operator fun invoke(): List<AlgorandSeedVaultSeed>
}

fun interface ImportAlgorandSeedVaultAccounts {
    suspend operator fun invoke(accounts: List<SeedVaultAccount>)
}
