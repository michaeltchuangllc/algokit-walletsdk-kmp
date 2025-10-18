package com.michaeltchuang.walletsdk.core.account.data.mapper.entity

import com.michaeltchuang.walletsdk.core.account.data.database.model.Algo25Entity
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import com.michaeltchuang.walletsdk.core.encryption.encryptByteArray

internal class Algo25EntityMapperImpl(
) : Algo25EntityMapper {
    override fun invoke(
        localAccount: LocalAccount.Algo25,
        privateKey: ByteArray,
    ): Algo25Entity =
        Algo25Entity(
            algoAddress = localAccount.algoAddress,
            encryptedSecretKey = encryptByteArray(privateKey),
        )
}
