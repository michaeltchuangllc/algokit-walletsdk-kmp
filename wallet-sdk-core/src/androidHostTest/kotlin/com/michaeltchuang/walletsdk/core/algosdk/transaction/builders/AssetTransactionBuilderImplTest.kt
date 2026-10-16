package com.michaeltchuang.walletsdk.core.algosdk.transaction.builders

import com.michaeltchuang.walletsdk.core.algosdk.transaction.model.Transaction
import com.michaeltchuang.walletsdk.core.algosdk.transaction.sdk.AlgoSdk
import com.michaeltchuang.walletsdk.core.algosdk.transaction.sdk.model.AssetTransactionPayload
import com.michaeltchuang.walletsdk.core.algosdk.transaction.sdk.model.SuggestedTransactionParams
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger

class AssetTransactionBuilderImplTest {
    private val algoSdk: AlgoSdk = mockk()

    private val sut = AssetTransactionBuilderImpl(algoSdk)

    @Test
    fun `EXPECT asset txn to be created`() {
        every {
            algoSdk.createAssetTransferTxn(
                ADDRESS,
                RECEIVER_ADDRESS,
                AMOUNT,
                ASSET_ID,
                NOTE,
                SUGGESTED_PARAMS,
            )
        } returns TXN_BYTE_ARRAY

        val result = sut(ASSET_TXN_PAYLOAD, SUGGESTED_PARAMS)

        val expected = Transaction.AssetTransaction(ADDRESS, TXN_BYTE_ARRAY)
        assertEquals(expected, result)
    }

    private companion object {
        const val ADDRESS = "WOLFYW4VEVVGEGVLQWEL4EMJ5SFCD3UCNKDH2DCUB5HQ6HLM6URZBMPXLI"
        const val RECEIVER_ADDRESS = "7IBEAXHK62XEJATU6Q4QYQCDFY475CEKNXGLYQO6QSGCLVMMK4SLVTYLMY"
        val AMOUNT = BigInteger.valueOf(1000)
        const val ASSET_ID = 226701642L
        val NOTE = "test note".toByteArray()
        val SUGGESTED_PARAMS = mockk<SuggestedTransactionParams>(relaxed = true)

        val ASSET_TXN_PAYLOAD =
            AssetTransactionPayload(
                senderAddress = ADDRESS,
                receiverAddress = RECEIVER_ADDRESS,
                amount = AMOUNT,
                noteInByteArray = NOTE,
                assetId = ASSET_ID,
            )
        val TXN_BYTE_ARRAY = byteArrayOf(1, 2, 3, 4, 5)
    }
}
