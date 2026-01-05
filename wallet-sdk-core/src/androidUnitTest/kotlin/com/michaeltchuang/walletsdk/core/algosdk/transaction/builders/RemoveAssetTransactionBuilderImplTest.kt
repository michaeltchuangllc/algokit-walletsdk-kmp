package com.michaeltchuang.walletsdk.core.algosdk.transaction.builders

import com.michaeltchuang.walletsdk.core.algosdk.transaction.model.Transaction
import com.michaeltchuang.walletsdk.core.algosdk.transaction.sdk.AlgoSdk
import com.michaeltchuang.walletsdk.core.algosdk.transaction.sdk.model.RemoveAssetTransactionPayload
import com.michaeltchuang.walletsdk.core.algosdk.transaction.sdk.model.SuggestedTransactionParams
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoveAssetTransactionBuilderImplTest {
    private val algoSdk: AlgoSdk = mockk()

    private val sut = RemoveAssetTransactionBuilderImpl(algoSdk)

    @Test
    fun `EXPECT remove asset txn to be created`() {
        every {
            algoSdk.createRemoveAssetTxn(ADDRESS, CREATOR_ADDRESS, ASSET_ID, SUGGESTED_PARAMS)
        } returns TXN_BYTE_ARRAY

        val result = sut(REMOVE_ASSET_PAYLOAD, SUGGESTED_PARAMS)

        val expected = Transaction.RemoveAssetTransaction(ADDRESS, TXN_BYTE_ARRAY)
        assertEquals(expected, result)
    }

    private companion object {
        const val ADDRESS = "WOLFYW4VEVVGEGVLQWEL4EMJ5SFCD3UCNKDH2DCUB5HQ6HLM6URZBMPXLI"
        const val ASSET_ID = 226701642L
        const val CREATOR_ADDRESS = "7IBEAXHK62XEJATU6Q4QYQCDFY475CEKNXGLYQO6QSGCLVMMK4SLVTYLMY"
        val SUGGESTED_PARAMS = mockk<SuggestedTransactionParams>(relaxed = true)
        val TXN_BYTE_ARRAY = byteArrayOf(1, 2, 3, 4, 5)
        val REMOVE_ASSET_PAYLOAD =
            RemoveAssetTransactionPayload(
                senderAddress = ADDRESS,
                assetId = ASSET_ID,
                creatorAddress = CREATOR_ADDRESS,
            )
    }
}
