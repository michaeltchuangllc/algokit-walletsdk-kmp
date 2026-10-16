package com.michaeltchuang.walletsdk.core.account.data.mapper.model

import com.michaeltchuang.walletsdk.core.account.data.database.model.HdKeyEntity
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount

internal class HdKeyMapperImpl : HdKeyMapper {
    override fun invoke(entity: HdKeyEntity): LocalAccount.HdKey =
        LocalAccount.HdKey(
            address = entity.algoAddress,
            publicKey = entity.publicKey,
            seedId = entity.seedId,
            account = entity.account,
            change = entity.change,
            keyIndex = entity.keyIndex,
            derivationType = entity.derivationType,
        )
}
