package com.michaeltchuang.walletsdk.core.account.data.mapper.entity

import com.michaeltchuang.walletsdk.core.account.data.database.model.Falcon24Entity
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import com.michaeltchuang.walletsdk.core.encryption.encryptByteArray

internal class Falcon24EntityMapperImpl() : Falcon24EntityMapper {
    override fun invoke(
        localAccount: LocalAccount.Falcon24,
        seedId: Int,
        privateKey: ByteArray,
    ): Falcon24Entity =
        Falcon24Entity(
            algoAddress = localAccount.algoAddress,
            seedId = seedId,
            publicKey = localAccount.publicKey,
            encryptedSecretKey = encryptByteArray(privateKey),
        )
}
