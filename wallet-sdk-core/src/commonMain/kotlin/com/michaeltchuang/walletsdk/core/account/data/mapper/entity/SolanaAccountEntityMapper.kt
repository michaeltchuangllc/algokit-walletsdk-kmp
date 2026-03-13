package com.michaeltchuang.walletsdk.core.account.data.mapper.entity

import com.michaeltchuang.walletsdk.core.account.data.database.model.SeedVaultEntity
import com.michaeltchuang.walletsdk.core.account.domain.model.local.SolanaAccount

internal fun interface SolanaAccountEntityMapper : (SolanaAccount) -> SeedVaultEntity

internal class SolanaAccountEntityMapperImpl : SolanaAccountEntityMapper {
    override fun invoke(account: SolanaAccount): SeedVaultEntity =
        SeedVaultEntity(
            publicKey = account.publicKey,
            address = account.address,
            chainId = account.chainId,
        )
}
