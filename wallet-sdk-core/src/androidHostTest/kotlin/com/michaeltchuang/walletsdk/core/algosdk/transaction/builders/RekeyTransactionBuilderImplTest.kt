package com.michaeltchuang.walletsdk.core.algosdk.transaction.builders

import com.michaeltchuang.walletsdk.core.algosdk.transaction.model.Transaction
import com.michaeltchuang.walletsdk.core.algosdk.transaction.sdk.AlgoSdk
import com.michaeltchuang.walletsdk.core.algosdk.transaction.sdk.model.RekeyTransactionPayload
import com.michaeltchuang.walletsdk.core.algosdk.transaction.sdk.model.SuggestedTransactionParams
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class RekeyTransactionBuilderImplTest {
    private val algoSdk: AlgoSdk = mockk()

    private val sut = RekeyTransactionBuilderImpl(algoSdk)

    @Test
    fun `EXPECT rekey transaction to be created`() {
        every {
            algoSdk.createRekeyTxn(
                REKEY_ADDRESS,
                ADDRESS,
                SUGGESTED_PARAMS,
            )
        } returns TXN_BYTE_ARRAY

        val result = sut(REKEY_TXN_PAYLOAD, SUGGESTED_PARAMS)

        val expected = Transaction.RekeyTransaction(REKEY_ADDRESS, TXN_BYTE_ARRAY)
        assertEquals(expected, result)
    }

    private companion object {
        const val ADDRESS = "7IBEAXHK62XEJATU6Q4QYQCDFY475CEKNXGLYQO6QSGCLVMMK4SLVTYLMY"
        const val REKEY_ADDRESS = "WOLFYW4VEVVGEGVLQWEL4EMJ5SFCD3UCNKDH2DCUB5HQ6HLM6URZBMPXLI"
        val SUGGESTED_PARAMS = mockk<SuggestedTransactionParams>(relaxed = true)
        val TXN_BYTE_ARRAY = byteArrayOf(1, 2, 3, 4, 5)

        val REKEY_TXN_PAYLOAD =
            RekeyTransactionPayload(
                address = REKEY_ADDRESS,
                rekeyAdminAddress = ADDRESS,
            )
    }
}
