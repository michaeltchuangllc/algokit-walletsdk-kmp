package com.michaeltchuang.walletsdk.core.account.domain.usecase.core

import com.michaeltchuang.walletsdk.core.account.domain.model.custom.CustomAccountInfo
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.custom.SetAccountCustomInfo
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.SaveFalcon25Account

internal class AddFalcon25AccountUseCase(
    private val saveFalcon25Account: SaveFalcon25Account,
    private val setCustomInfo: SetAccountCustomInfo,
) : AddFalcon25Account {
    override suspend fun invoke(
        address: String,
        publicKey: ByteArray,
        privateKey: ByteArray,
        entropy: ByteArray,
        isBackedUp: Boolean,
        customName: String?,
        orderIndex: Int,
    ) {
        val account = LocalAccount.Falcon25(address, publicKey)
        saveFalcon25Account(account, privateKey, entropy)
        setCustomInfo(CustomAccountInfo(address, customName, orderIndex, isBackedUp))
    }
}
