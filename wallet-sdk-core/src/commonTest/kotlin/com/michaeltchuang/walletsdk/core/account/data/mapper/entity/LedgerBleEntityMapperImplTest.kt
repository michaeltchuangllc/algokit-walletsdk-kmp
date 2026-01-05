package com.michaeltchuang.walletsdk.core.account.data.mapper.entity

import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import kotlin.test.Test
import kotlin.test.assertEquals

class LedgerBleEntityMapperImplTest {
    private val sut = LedgerBleEntityMapperImpl()

    @Test
    fun `EXPECT LedgerBleEntity to be created from LocalAccount LedgerBle`() {
        val localAccount =
            LocalAccount.LedgerBle(
                algoAddress = ADDRESS,
                deviceMacAddress = MAC_ADDRESS,
                bluetoothName = BLUETOOTH_NAME,
                indexInLedger = INDEX_IN_LEDGER,
            )

        val result = sut(localAccount)

        assertEquals(ADDRESS, result.algoAddress)
        assertEquals(MAC_ADDRESS, result.deviceMacAddress)
        assertEquals(BLUETOOTH_NAME, result.bluetoothName)
        assertEquals(INDEX_IN_LEDGER, result.accountIndexInLedger)
    }

    @Test
    fun `EXPECT LedgerBleEntity to be created with null bluetooth name`() {
        val localAccount =
            LocalAccount.LedgerBle(
                algoAddress = ADDRESS,
                deviceMacAddress = MAC_ADDRESS,
                bluetoothName = null,
                indexInLedger = INDEX_IN_LEDGER,
            )

        val result = sut(localAccount)

        assertEquals(ADDRESS, result.algoAddress)
        assertEquals(MAC_ADDRESS, result.deviceMacAddress)
        assertEquals(null, result.bluetoothName)
        assertEquals(INDEX_IN_LEDGER, result.accountIndexInLedger)
    }

    private companion object {
        const val ADDRESS = "WOLFYW4VEVVGEGVLQWEL4EMJ5SFCD3UCNKDH2DCUB5HQ6HLM6URZBMPXLI"
        const val MAC_ADDRESS = "AA:BB:CC:DD:EE:FF"
        const val BLUETOOTH_NAME = "Ledger Nano X"
        const val INDEX_IN_LEDGER = 0
    }
}
