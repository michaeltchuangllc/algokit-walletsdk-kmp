package com.michaeltchuang.walletsdk.core.account.domain.repository.algorand

import com.michaeltchuang.walletsdk.core.account.domain.model.algorand.AlgorandSeedVaultSeed

interface AlgorandSeedVaultRepository {
    suspend fun getAuthorizedSeeds(): List<AlgorandSeedVaultSeed>
}
