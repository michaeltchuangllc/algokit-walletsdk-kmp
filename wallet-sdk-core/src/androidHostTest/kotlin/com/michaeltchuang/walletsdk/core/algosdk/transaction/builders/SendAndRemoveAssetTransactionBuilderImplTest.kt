package com.michaeltchuang.walletsdk.core.algosdk.transaction.builders

import com.michaeltchuang.walletsdk.core.algosdk.transaction.model.Transaction
import com.michaeltchuang.walletsdk.core.algosdk.transaction.sdk.AlgoSdk
import com.michaeltchuang.walletsdk.core.algosdk.transaction.sdk.model.SendAndRemoveAssetTransactionPayload
import com.michaeltchuang.walletsdk.core.algosdk.transaction.sdk.model.SuggestedTransactionParams
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger

class SendAndRemoveAssetTransactionBuilderImplTest {
    private val algoSdk: AlgoSdk = mockk()

    private val sut = SendAndRemoveAssetTransactionBuilderImpl(algoSdk)

    @Test
    fun `EXPECT send and remove asset txn to be created`() {
        every {
            algoSdk.createSendAndRemoveAssetTxn(
                ADDRESS,
                RECEIVER_ADDRESS,
                ASSET_ID,
                AMOUNT,
                NOTE,
                SUGGESTED_PARAMS,
            )
        } returns TXN_BYTE_ARRAY

        val result = sut(SEND_AND_REMOVE_ASSET_PAYLOAD, SUGGESTED_PARAMS)

        val expected = Transaction.SendAndRemoveAssetTransaction(ADDRESS, TXN_BYTE_ARRAY)
        assertEquals(expected, result)
    }

    private companion object {
        const val ADDRESS = "WOLFYW4VEVVGEGVLQWEL4EMJ5SFCD3UCNKDH2DCUB5HQ6HLM6URZBMPXLI"
        const val ASSET_ID = 226701642L
        const val RECEIVER_ADDRESS = "7IBEAXHK62XEJATU6Q4QYQCDFY475CEKNXGLYQO6QSGCLVMMK4SLVTYLMY"
        val AMOUNT = BigInteger.valueOf(1000)
        val NOTE = "test note".toByteArray()
        val SUGGESTED_PARAMS = mockk<SuggestedTransactionParams>(relaxed = true)
        val TXN_BYTE_ARRAY = byteArrayOf(1, 2, 3, 4, 5)
        val SEND_AND_REMOVE_ASSET_PAYLOAD =
            SendAndRemoveAssetTransactionPayload(
                senderAddress = ADDRESS,
                assetId = ASSET_ID,
                receiverAddress = RECEIVER_ADDRESS,
                amount = AMOUNT,
                noteInByteArray = NOTE,
            )
    }
}
