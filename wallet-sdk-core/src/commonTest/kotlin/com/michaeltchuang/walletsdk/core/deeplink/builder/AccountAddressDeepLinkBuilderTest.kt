package com.michaeltchuang.walletsdk.core.deeplink.builder

import com.michaeltchuang.walletsdk.core.deeplink.model.DeepLink
import com.michaeltchuang.walletsdk.core.deeplink.model.DeepLinkPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountAddressDeepLinkBuilderTest {
    private val sut = AccountAddressDeepLinkBuilder()

    @Test
    fun `EXPECT deeplink meets requirements WHEN only account address is provided`() {
        val payload =
            DeepLinkPayload(
                accountAddress = ADDRESS,
                rawDeepLinkUri = "algorand://$ADDRESS",
            )

        val result = sut.doesDeeplinkMeetTheRequirements(payload)

        assertTrue(result)
    }

    @Test
    fun `EXPECT deeplink meets requirements WHEN account address and label are provided`() {
        val payload =
            DeepLinkPayload(
                accountAddress = ADDRESS,
                label = LABEL,
                rawDeepLinkUri = "algorand://$ADDRESS?label=$LABEL",
            )

        val result = sut.doesDeeplinkMeetTheRequirements(payload)

        assertTrue(result)
    }

    @Test
    fun `EXPECT deeplink does not meet requirements WHEN account address is null`() {
        val payload =
            DeepLinkPayload(
                accountAddress = null,
                rawDeepLinkUri = "algorand://",
            )

        val result = sut.doesDeeplinkMeetTheRequirements(payload)

        assertFalse(result)
    }

    @Test
    fun `EXPECT deeplink does not meet requirements WHEN asset id is provided`() {
        val payload =
            DeepLinkPayload(
                accountAddress = ADDRESS,
                assetId = ASSET_ID,
                rawDeepLinkUri = "algorand://$ADDRESS?asset=$ASSET_ID",
            )

        val result = sut.doesDeeplinkMeetTheRequirements(payload)

        assertFalse(result)
    }

    @Test
    fun `EXPECT deeplink does not meet requirements WHEN wallet connect url is provided`() {
        val payload =
            DeepLinkPayload(
                accountAddress = ADDRESS,
                walletConnectUrl = "wc://test",
                rawDeepLinkUri = "wc://test",
            )

        val result = sut.doesDeeplinkMeetTheRequirements(payload)

        assertFalse(result)
    }

    @Test
    fun `EXPECT deeplink does not meet requirements WHEN type is keyreg`() {
        val payload =
            DeepLinkPayload(
                accountAddress = ADDRESS,
                type = "keyreg",
                rawDeepLinkUri = "algorand://$ADDRESS?type=keyreg",
            )

        val result = sut.doesDeeplinkMeetTheRequirements(payload)

        assertFalse(result)
    }

    @Test
    fun `EXPECT AccountAddress deeplink to be created with address and label`() {
        val payload =
            DeepLinkPayload(
                accountAddress = ADDRESS,
                label = LABEL,
                rawDeepLinkUri = "algorand://$ADDRESS?label=$LABEL",
            )

        val result = sut.createDeepLink(payload)

        assertTrue(result is DeepLink.AccountAddress)
        assertEquals(ADDRESS, result.address)
        assertEquals(LABEL, result.label)
    }

    @Test
    fun `EXPECT AccountAddress deeplink to be created without label`() {
        val payload =
            DeepLinkPayload(
                accountAddress = ADDRESS,
                rawDeepLinkUri = "algorand://$ADDRESS",
            )

        val result = sut.createDeepLink(payload)

        assertTrue(result is DeepLink.AccountAddress)
        assertEquals(ADDRESS, result.address)
        assertEquals(null, result.label)
    }

    private companion object {
        const val ADDRESS = "WOLFYW4VEVVGEGVLQWEL4EMJ5SFCD3UCNKDH2DCUB5HQ6HLM6URZBMPXLI"
        const val LABEL = "My Account"
        const val ASSET_ID = 226701642L
    }
}
