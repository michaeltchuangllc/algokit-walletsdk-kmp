package com.michaeltchuang.walletsdk.core.account.domain.usecase.local

import com.michaeltchuang.walletsdk.core.account.domain.model.local.HdSeedFirstAddress
import com.michaeltchuang.walletsdk.core.account.domain.repository.local.Falcon24AccountRepository
import com.michaeltchuang.walletsdk.core.account.domain.repository.local.HdKeyAccountRepository
import com.michaeltchuang.walletsdk.core.account.domain.repository.local.HdSeedRepository

class GetAllHdSeedFirstAddressesUseCase(
    private val hdSeedRepository: HdSeedRepository,
    private val hdKeyRepository: HdKeyAccountRepository,
    private val falconRepository: Falcon24AccountRepository,
) : GetAllHdSeedFirstAddresses {
    override suspend fun invoke(): List<HdSeedFirstAddress> {
        // Get all HD seeds
        val allSeeds = hdSeedRepository.getAllHdSeeds()

        // For each seed, find the account with index 0 (first address)
        return allSeeds.mapNotNull { seed ->
            // Get all Falcon24 accounts for this seed
            val falcon24Accounts = falconRepository.getAll()

            // Find the first account (index 0) for this seed
            val firstAccount =
                falcon24Accounts.firstOrNull { account ->
                    account.seedId == seed.seedId
                }

            // If we found the first address, return it
            firstAccount?.let {
                HdSeedFirstAddress(
                    seedId = seed.seedId,
                    firstAddress = it.algoAddress,
                )
            }
        }
    }
}
