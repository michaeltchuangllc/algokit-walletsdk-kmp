package com.michaeltchuang.walletsdk.core.deeplink.builder

import com.michaeltchuang.walletsdk.core.deeplink.model.DeepLink
import com.michaeltchuang.walletsdk.core.deeplink.model.DeepLinkPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WalletConnectConnectionDeepLinkBuilderTest {
    private val sut = WalletConnectConnectionDeepLinkBuilder()

    @Test
    fun `EXPECT deeplink meets requirements WHEN only wallet connect url is provided`() {
        val payload =
            DeepLinkPayload(
                walletConnectUrl = WC_URL,
                rawDeepLinkUri = WC_URL,
            )

        val result = sut.doesDeeplinkMeetTheRequirements(payload)

        assertTrue(result)
    }

    @Test
    fun `EXPECT deeplink does not meet requirements WHEN wallet connect url is null`() {
        val payload =
            DeepLinkPayload(
                walletConnectUrl = null,
                rawDeepLinkUri = "algorand://",
            )

        val result = sut.doesDeeplinkMeetTheRequirements(payload)

        assertFalse(result)
    }

    @Test
    fun `EXPECT deeplink does not meet requirements WHEN account address is also provided`() {
        val payload =
            DeepLinkPayload(
                walletConnectUrl = WC_URL,
                accountAddress = ADDRESS,
                rawDeepLinkUri = WC_URL,
            )

        val result = sut.doesDeeplinkMeetTheRequirements(payload)

        assertFalse(result)
    }

    @Test
    fun `EXPECT deeplink does not meet requirements WHEN asset id is also provided`() {
        val payload =
            DeepLinkPayload(
                walletConnectUrl = WC_URL,
                assetId = ASSET_ID,
                rawDeepLinkUri = WC_URL,
            )

        val result = sut.doesDeeplinkMeetTheRequirements(payload)

        assertFalse(result)
    }

    @Test
    fun `EXPECT WalletConnectConnection deeplink to be created`() {
        val payload =
            DeepLinkPayload(
                walletConnectUrl = WC_URL,
                rawDeepLinkUri = WC_URL,
            )

        val result = sut.createDeepLink(payload)

        assertTrue(result is DeepLink.WalletConnectConnection)
        assertEquals(WC_URL, result.uri)
    }

    private companion object {
        const val WC_URL =
            "wc:b562a118-0cbd-4f4f-92af-e58bf0a9dfb8@1?bridge=https%3A%2F%2Fwallet-connect.perawallet.app&key=test"
        const val ADDRESS = "WOLFYW4VEVVGEGVLQWEL4EMJ5SFCD3UCNKDH2DCUB5HQ6HLM6URZBMPXLI"
        const val ASSET_ID = 226701642L
    }
}
