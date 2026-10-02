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
        val allSeeds = hdSeedRepository.getAllHdSeeds()
        val falcon24Accounts = falconRepository.getAll()
        val hdAccounts =
            hdKeyRepository.getAll().sortedWith(
                compareBy({ it.account }, { it.change }, { it.keyIndex }, { it.derivationType }, { it.address }),
            )
        return allSeeds.mapNotNull { seed ->
            // Preserve the existing Falcon24 representative for previously registered passkeys,
            // but also expose ordinary HD wallets (which need not contain a Falcon24 account).
            val firstAddress =
                falcon24Accounts.firstOrNull { it.seedId == seed.seedId }?.address
                    ?: hdAccounts.firstOrNull { it.seedId == seed.seedId }?.address
            firstAddress?.let {
                HdSeedFirstAddress(
                    seedId = seed.seedId,
                    firstAddress = it,
                )
            }
        }
    }
}
