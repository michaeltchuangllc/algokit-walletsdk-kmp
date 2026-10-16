package com.michaeltchuang.walletsdk.core.algosdk.transaction.builders

import com.michaeltchuang.walletsdk.core.algosdk.transaction.model.Transaction
import com.michaeltchuang.walletsdk.core.algosdk.transaction.sdk.AlgoSdk
import com.michaeltchuang.walletsdk.core.algosdk.transaction.sdk.model.AlgoTransactionPayload
import com.michaeltchuang.walletsdk.core.algosdk.transaction.sdk.model.SuggestedTransactionParams
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger

class AlgoTransactionBuilderImplTest {
    private val algoSdk: AlgoSdk = mockk()

    private val sut = AlgoTransactionBuilderImpl(algoSdk)

    @Test
    fun `EXPECT algo txn to be created`() {
        every {
            algoSdk.createAlgoTransferTxn(
                ADDRESS,
                RECEIVER_ADDRESS,
                AMOUNT,
                IS_MAX,
                NOTE,
                SUGGESTED_PARAMS,
            )
        } returns TXN_BYTE_ARRAY

        val result = sut(ALGO_TXN_PAYLOAD, SUGGESTED_PARAMS)

        val expected = Transaction.AlgoTransaction(ADDRESS, TXN_BYTE_ARRAY)
        assertEquals(expected, result)
    }

    private companion object {
        const val ADDRESS = "WOLFYW4VEVVGEGVLQWEL4EMJ5SFCD3UCNKDH2DCUB5HQ6HLM6URZBMPXLI"
        const val RECEIVER_ADDRESS = "7IBEAXHK62XEJATU6Q4QYQCDFY475CEKNXGLYQO6QSGCLVMMK4SLVTYLMY"
        val AMOUNT = BigInteger.valueOf(1000000)
        const val IS_MAX = false
        val NOTE = "test note".toByteArray()
        val SUGGESTED_PARAMS = mockk<SuggestedTransactionParams>(relaxed = true)

        val ALGO_TXN_PAYLOAD =
            AlgoTransactionPayload(
                senderAddress = ADDRESS,
                receiverAddress = RECEIVER_ADDRESS,
                amount = AMOUNT,
                isMaxAmount = IS_MAX,
                noteInByteArray = NOTE,
            )
        val TXN_BYTE_ARRAY = byteArrayOf(1, 2, 3, 4, 5)
    }
}
