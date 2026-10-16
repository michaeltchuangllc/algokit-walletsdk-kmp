package com.michaeltchuang.walletsdk.core.account.data.mapper.model

import com.michaeltchuang.walletsdk.core.account.data.database.model.Algo25Entity
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount

internal class Algo25MapperImpl : Algo25Mapper {
    override fun invoke(entity: Algo25Entity): LocalAccount.Algo25 =
        LocalAccount.Algo25(
            address = entity.algoAddress,
        )
}
