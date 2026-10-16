package com.michaeltchuang.walletsdk.core.account.data.mapper.entity

import com.michaeltchuang.walletsdk.core.account.data.database.model.LedgerBleEntity
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount

internal class LedgerBleEntityMapperImpl : LedgerBleEntityMapper {
    override fun invoke(localAccount: LocalAccount.LedgerBle): LedgerBleEntity =
        LedgerBleEntity(
            algoAddress = localAccount.address,
            deviceMacAddress = localAccount.deviceMacAddress,
            accountIndexInLedger = localAccount.indexInLedger,
            bluetoothName = localAccount.bluetoothName,
        )
}
