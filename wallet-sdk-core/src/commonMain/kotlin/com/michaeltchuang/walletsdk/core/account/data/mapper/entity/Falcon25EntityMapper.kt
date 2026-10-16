package com.michaeltchuang.walletsdk.core.account.data.mapper.entity

import com.michaeltchuang.walletsdk.core.account.data.database.model.Falcon25Entity
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount

internal interface Falcon25EntityMapper {
    operator fun invoke(
        account: LocalAccount.Falcon25,
        privateKey: ByteArray,
        entropy: ByteArray,
        seed: ByteArray,
    ): Falcon25Entity
}
