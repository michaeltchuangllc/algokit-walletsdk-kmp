package com.michaeltchuang.walletsdk.core.deeplink.builder

import com.michaeltchuang.walletsdk.core.deeplink.model.DeepLink
import com.michaeltchuang.walletsdk.core.deeplink.model.DeepLinkPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AssetOptInDeepLinkBuilderTest {
    private val sut = AssetOptInDeepLinkBuilder()

    @Test
    fun `EXPECT deeplink meets requirements WHEN asset id and amount 0 are provided`() {
        val payload =
            DeepLinkPayload(
                assetId = ASSET_ID,
                amount = "0",
                rawDeepLinkUri = "algorand://?asset=$ASSET_ID&amount=0",
            )

        val result = sut.doesDeeplinkMeetTheRequirements(payload)

        assertTrue(result)
    }

    @Test
    fun `EXPECT deeplink does not meet requirements WHEN amount is not 0`() {
        val payload =
            DeepLinkPayload(
                assetId = ASSET_ID,
                amount = "100",
                rawDeepLinkUri = "algorand://?asset=$ASSET_ID&amount=100",
            )

        val result = sut.doesDeeplinkMeetTheRequirements(payload)

        assertFalse(result)
    }

    @Test
    fun `EXPECT deeplink does not meet requirements WHEN asset id is null`() {
        val payload =
            DeepLinkPayload(
                assetId = null,
                amount = "0",
                rawDeepLinkUri = "algorand://?amount=0",
            )

        val result = sut.doesDeeplinkMeetTheRequirements(payload)

        assertFalse(result)
    }

    @Test
    fun `EXPECT deeplink does not meet requirements WHEN account address is provided`() {
        val payload =
            DeepLinkPayload(
                assetId = ASSET_ID,
                amount = "0",
                accountAddress = ADDRESS,
                rawDeepLinkUri = "algorand://$ADDRESS?asset=$ASSET_ID&amount=0",
            )

        val result = sut.doesDeeplinkMeetTheRequirements(payload)

        assertFalse(result)
    }

    @Test
    fun `EXPECT AssetOptIn deeplink to be created`() {
        val payload =
            DeepLinkPayload(
                assetId = ASSET_ID,
                amount = "0",
                rawDeepLinkUri = "algorand://?asset=$ASSET_ID&amount=0",
            )

        val result = sut.createDeepLink(payload)

        assertTrue(result is DeepLink.AssetOptIn)
        assertEquals(ASSET_ID, result.assetId)
    }

    @Test
    fun `EXPECT Undefined deeplink WHEN asset id is null`() {
        val payload =
            DeepLinkPayload(
                assetId = null,
                amount = "0",
                rawDeepLinkUri = "algorand://?amount=0",
            )

        val result = sut.createDeepLink(payload)

        assertTrue(result is DeepLink.Undefined)
        assertEquals("algorand://?amount=0", result.url)
    }

    private companion object {
        const val ASSET_ID = 226701642L
        const val ADDRESS = "WOLFYW4VEVVGEGVLQWEL4EMJ5SFCD3UCNKDH2DCUB5HQ6HLM6URZBMPXLI"
    }
}
