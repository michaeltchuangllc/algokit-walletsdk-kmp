package com.michaeltchuang.walletsdk.core.account.data.mapper.entity

import com.michaeltchuang.walletsdk.core.account.data.database.model.CustomHdSeedInfoEntity
import com.michaeltchuang.walletsdk.core.account.domain.model.custom.CustomHdSeedInfo

internal class CustomHdSeedInfoEntityMapperImpl : CustomHdSeedInfoEntityMapper {
    override fun invoke(customHdSeedInfo: CustomHdSeedInfo): CustomHdSeedInfoEntity =
        CustomHdSeedInfoEntity(
            seedId = customHdSeedInfo.seedId,
            entropyCustomName = customHdSeedInfo.entropyCustomName,
            orderIndex = customHdSeedInfo.orderIndex,
            isBackedUp = customHdSeedInfo.isBackedUp,
        )
}
