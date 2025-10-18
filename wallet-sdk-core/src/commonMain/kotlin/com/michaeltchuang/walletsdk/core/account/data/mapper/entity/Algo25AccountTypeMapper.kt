package com.michaeltchuang.walletsdk.core.account.data.mapper.entity

import com.michaeltchuang.walletsdk.core.account.domain.model.core.AccountCreation

interface Algo25AccountTypeMapper {
    operator fun invoke(
        secretKey: ByteArray,
    ): AccountCreation.Type.Algo25
}