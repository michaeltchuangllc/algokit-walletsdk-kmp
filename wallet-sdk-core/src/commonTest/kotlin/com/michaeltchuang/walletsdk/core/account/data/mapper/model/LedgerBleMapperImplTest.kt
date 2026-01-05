package com.michaeltchuang.walletsdk.core.account.data.mapper.model

import com.michaeltchuang.walletsdk.core.account.data.database.model.LedgerBleEntity
import kotlin.test.Test
import kotlin.test.assertEquals

class LedgerBleMapperImplTest {
    private val sut = LedgerBleMapperImpl()

    @Test
    fun `EXPECT LocalAccount LedgerBle to be created from LedgerBleEntity`() {
        val entity =
            LedgerBleEntity(
                algoAddress = ADDRESS,
                deviceMacAddress = MAC_ADDRESS,
                bluetoothName = BLUETOOTH_NAME,
                accountIndexInLedger = INDEX_IN_LEDGER,
            )

        val result = sut(entity)

        assertEquals(ADDRESS, result.algoAddress)
        assertEquals(MAC_ADDRESS, result.deviceMacAddress)
        assertEquals(BLUETOOTH_NAME, result.bluetoothName)
        assertEquals(INDEX_IN_LEDGER, result.indexInLedger)
    }

    @Test
    fun `EXPECT LocalAccount LedgerBle to be created with null bluetooth name`() {
        val entity =
            LedgerBleEntity(
                algoAddress = ADDRESS,
                deviceMacAddress = MAC_ADDRESS,
                bluetoothName = null,
                accountIndexInLedger = INDEX_IN_LEDGER,
            )

        val result = sut(entity)

        assertEquals(ADDRESS, result.algoAddress)
        assertEquals(MAC_ADDRESS, result.deviceMacAddress)
        assertEquals(null, result.bluetoothName)
        assertEquals(INDEX_IN_LEDGER, result.indexInLedger)
    }

    private companion object {
        const val ADDRESS = "WOLFYW4VEVVGEGVLQWEL4EMJ5SFCD3UCNKDH2DCUB5HQ6HLM6URZBMPXLI"
        const val MAC_ADDRESS = "AA:BB:CC:DD:EE:FF"
        const val BLUETOOTH_NAME = "Ledger Nano X"
        const val INDEX_IN_LEDGER = 0
    }
}
