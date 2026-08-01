package com.michaeltchuang.walletsdk.core.account.data.mapper.entity

import com.michaeltchuang.walletsdk.core.account.data.database.model.Falcon25Entity
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import com.michaeltchuang.walletsdk.core.encryption.encryptByteArray

internal class Falcon25EntityMapperImpl : Falcon25EntityMapper {
    override fun invoke(
        account: LocalAccount.Falcon25,
        privateKey: ByteArray,
        entropy: ByteArray,
    ): Falcon25Entity =
        Falcon25Entity(
            algoAddress = account.address,
            publicKey = account.publicKey,
            encryptedPrivateKey = encryptByteArray(privateKey),
            encryptedEntropy = encryptByteArray(entropy),
        )
}
