package com.michaeltchuang.walletsdk.core.algosdk.transaction.builders

import com.michaeltchuang.walletsdk.core.algosdk.transaction.model.Transaction
import com.michaeltchuang.walletsdk.core.algosdk.transaction.sdk.AlgoSdk
import com.michaeltchuang.walletsdk.core.algosdk.transaction.sdk.model.AddAssetTransactionPayload
import com.michaeltchuang.walletsdk.core.algosdk.transaction.sdk.model.SuggestedTransactionParams
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class AddAssetTransactionBuilderImplTest {
    private val algoSdk: AlgoSdk = mockk()

    private val sut = AddAssetTransactionBuilderBuilderImpl(algoSdk)

    @Test
    fun `EXPECT add asset txn to be created`() {
        every {
            algoSdk.createAddAssetTxn(
                ADDRESS,
                ASSET_ID,
                SUGGESTED_PARAMS,
            )
        } returns TXN_BYTE_ARRAY

        val result = sut(ADD_ASSET_PAYLOAD, SUGGESTED_PARAMS)

        val expected = Transaction.AddAssetTransaction(ADDRESS, TXN_BYTE_ARRAY)
        assertEquals(expected, result)
    }

    private companion object {
        const val ADDRESS = "WOLFYW4VEVVGEGVLQWEL4EMJ5SFCD3UCNKDH2DCUB5HQ6HLM6URZBMPXLI"
        const val ASSET_ID = 226701642L

        val ADD_ASSET_PAYLOAD =
            AddAssetTransactionPayload(
                address = ADDRESS,
                assetId = ASSET_ID,
            )

        val SUGGESTED_PARAMS = mockk<SuggestedTransactionParams>(relaxed = true)

        val TXN_BYTE_ARRAY = byteArrayOf(1, 2, 3, 4, 5)
    }
}
