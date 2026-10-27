package com.michaeltchuang.walletsdk.core.account.data.mapper.model

import com.michaeltchuang.walletsdk.core.account.data.database.model.SeedVaultEntity
import com.michaeltchuang.walletsdk.core.account.domain.model.local.SeedVaultAccount

internal fun interface SeedVaultAccountMapper : (SeedVaultEntity) -> SeedVaultAccount

internal class SeedVaultAccountMapperImpl : SeedVaultAccountMapper {
    override fun invoke(entity: SeedVaultEntity): SeedVaultAccount =
        SeedVaultAccount(
            publicKey = entity.publicKey,
            address = entity.address,
            chainId = entity.chainId,
            accountName = entity.accountName,
        )
}
