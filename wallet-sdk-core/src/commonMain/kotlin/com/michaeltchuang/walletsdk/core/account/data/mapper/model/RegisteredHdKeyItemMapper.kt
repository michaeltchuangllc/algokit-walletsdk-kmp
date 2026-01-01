package com.michaeltchuang.walletsdk.core.account.data.mapper.model

import com.michaeltchuang.walletsdk.core.account.domain.model.local.RegisteredHdKey
import com.michaeltchuang.walletsdk.core.account.domain.model.local.RegisteredHdKeyItem

interface RegisteredHdKeyItemMapper {
    operator fun invoke(hdKey: RegisteredHdKey): RegisteredHdKeyItem
}
