package com.michaeltchuang.walletsdk.core.account.data.mapper.model

import com.michaeltchuang.walletsdk.core.account.data.database.model.Falcon25Entity
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount

internal class Falcon25MapperImpl : Falcon25Mapper {
    override fun invoke(entity: Falcon25Entity) = LocalAccount.Falcon25(entity.algoAddress, entity.publicKey)
}
