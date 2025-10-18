package com.michaeltchuang.walletsdk.core.account.data.mapper.entity

import com.michaeltchuang.walletsdk.core.account.domain.model.core.AccountCreation
import com.michaeltchuang.walletsdk.core.algosdk.bip39.model.HdKeyAddress
import com.michaeltchuang.walletsdk.core.algosdk.bip39.model.HdKeyAddressDerivationType
import com.michaeltchuang.walletsdk.core.encryption.encryptByteArray

internal class DefaultAccountCreationHdKeyTypeMapperImpl() : AccountCreationHdKeyTypeMapper {
    override fun invoke(
        entropy: ByteArray,
        hdKeyAddress: HdKeyAddress,
        seedId: Int?,
    ): AccountCreation.Type.HdKey =
        with(hdKeyAddress) {
            AccountCreation.Type.HdKey(
                publicKey,
                encryptByteArray(privateKey) ,
                encryptByteArray(entropy),
                index.accountIndex,
                index.changeIndex,
                index.keyIndex,
                HdKeyAddressDerivationType.Peikert.value,
                seedId,
            )
        }
}
