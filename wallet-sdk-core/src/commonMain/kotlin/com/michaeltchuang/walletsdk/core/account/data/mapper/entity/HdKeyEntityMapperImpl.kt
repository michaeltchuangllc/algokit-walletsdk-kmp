package com.michaeltchuang.walletsdk.core.account.data.mapper.entity

import com.michaeltchuang.walletsdk.core.account.data.database.model.HdKeyEntity
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import com.michaeltchuang.walletsdk.core.encryption.encryptByteArray

internal class HdKeyEntityMapperImpl(
) : HdKeyEntityMapper {
    override fun invoke(
        localAccount: LocalAccount.HdKey,
        privateKey: ByteArray,
    ): HdKeyEntity =
        HdKeyEntity(
            algoAddress = localAccount.algoAddress,
            publicKey = localAccount.publicKey,
            encryptedPrivateKey =encryptByteArray(privateKey),
            seedId = localAccount.seedId,
            account = localAccount.account,
            change = localAccount.change,
            keyIndex = localAccount.keyIndex,
            derivationType = localAccount.derivationType,
        )
}
