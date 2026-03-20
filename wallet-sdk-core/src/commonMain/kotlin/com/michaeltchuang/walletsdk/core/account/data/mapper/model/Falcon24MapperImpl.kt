package com.michaeltchuang.walletsdk.core.account.data.mapper.model

import com.michaeltchuang.walletsdk.core.account.data.database.model.Falcon24Entity
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount

internal class Falcon24MapperImpl : Falcon24Mapper {
    override fun invoke(entity: Falcon24Entity): LocalAccount.Falcon24 =
        LocalAccount.Falcon24(
            address = entity.algoAddress,
            seedId = entity.seedId,
            publicKey = entity.publicKey,
        )
}
