package com.michaeltchuang.walletsdk.core.account.data.mapper.model

import com.michaeltchuang.walletsdk.core.account.data.database.model.SeedVaultEntity
import com.michaeltchuang.walletsdk.core.account.domain.model.local.SolanaAccount

internal fun interface SolanaAccountMapper : (SeedVaultEntity) -> SolanaAccount

internal class SolanaAccountMapperImpl : SolanaAccountMapper {
    override fun invoke(entity: SeedVaultEntity): SolanaAccount =
        SolanaAccount(
            publicKey = entity.publicKey,
            address = entity.address,
            chainId = entity.chainId,
            accountName = entity.accountName,
        )
}
