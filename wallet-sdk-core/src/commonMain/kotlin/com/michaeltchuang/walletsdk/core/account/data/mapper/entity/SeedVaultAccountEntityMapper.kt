package com.michaeltchuang.walletsdk.core.account.data.mapper.entity

import com.michaeltchuang.walletsdk.core.account.data.database.model.SeedVaultEntity
import com.michaeltchuang.walletsdk.core.account.domain.model.local.SeedVaultAccount

internal fun interface SeedVaultAccountEntityMapper : (SeedVaultAccount) -> SeedVaultEntity

internal class SeedVaultAccountEntityMapperImpl : SeedVaultAccountEntityMapper {
    override fun invoke(account: SeedVaultAccount): SeedVaultEntity =
        SeedVaultEntity(
            publicKey = account.publicKey,
            address = account.address,
            chainId = account.chainId,
            accountName = account.accountName,
        )
}
