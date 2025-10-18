package com.michaeltchuang.walletsdk.core.account.data.mapper.entity

import com.michaeltchuang.walletsdk.core.account.domain.model.core.AccountCreation
import com.michaeltchuang.walletsdk.core.encryption.encryptByteArray

class Algo25AccountTypeMapperImpl : Algo25AccountTypeMapper {
    override fun invoke(secretKey: ByteArray): AccountCreation.Type.Algo25 {
        return AccountCreation.Type.Algo25(encryptByteArray(secretKey))
    }
}