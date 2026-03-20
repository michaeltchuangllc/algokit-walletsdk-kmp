package com.michaeltchuang.walletsdk.core.account.data.mapper.entity

import com.michaeltchuang.walletsdk.core.account.data.database.model.NoAuthEntity
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount

internal class NoAuthEntityMapperImpl : NoAuthEntityMapper {
    override fun invoke(localAccount: LocalAccount.NoAuth): NoAuthEntity =
        NoAuthEntity(
            algoAddress = localAccount.address,
        )

    override fun invoke(address: String): NoAuthEntity = NoAuthEntity(algoAddress = address)
}
