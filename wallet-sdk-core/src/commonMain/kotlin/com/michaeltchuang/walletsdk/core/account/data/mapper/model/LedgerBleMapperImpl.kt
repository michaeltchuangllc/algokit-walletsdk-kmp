package com.michaeltchuang.walletsdk.core.account.data.mapper.model

import com.michaeltchuang.walletsdk.core.account.data.database.model.LedgerBleEntity
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount

internal class LedgerBleMapperImpl : LedgerBleMapper {
    override fun invoke(entity: LedgerBleEntity): LocalAccount.LedgerBle =
        LocalAccount.LedgerBle(
            address = entity.algoAddress,
            deviceMacAddress = entity.deviceMacAddress,
            indexInLedger = entity.accountIndexInLedger,
            bluetoothName = entity.bluetoothName,
        )
}
