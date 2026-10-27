package com.michaeltchuang.walletsdk.core.account.data.repository

import com.michaeltchuang.walletsdk.core.account.domain.model.algorand.AlgorandSeedVaultSeed
import com.michaeltchuang.walletsdk.core.account.domain.repository.algorand.AlgorandSeedVaultRepository

internal class AlgorandSeedVaultRepositoryImpl : AlgorandSeedVaultRepository {
    override suspend fun getAuthorizedSeeds(): List<AlgorandSeedVaultSeed> =
        throw UnsupportedOperationException("Algorand Seed Vault is not supported on iOS")
}
