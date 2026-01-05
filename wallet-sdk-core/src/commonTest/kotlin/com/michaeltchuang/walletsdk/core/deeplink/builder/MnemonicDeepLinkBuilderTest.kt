package com.michaeltchuang.walletsdk.core.deeplink.builder

import com.michaeltchuang.walletsdk.core.deeplink.model.DeepLink
import com.michaeltchuang.walletsdk.core.deeplink.model.DeepLinkPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MnemonicDeepLinkBuilderTest {
    private val sut = MnemonicDeepLinkBuilder()

    @Test
    fun `EXPECT deeplink meets requirements WHEN only mnemonic is provided`() {
        val payload =
            DeepLinkPayload(
                mnemonic = MNEMONIC,
                rawDeepLinkUri = "algorand://mnemonic?m=$MNEMONIC",
            )

        val result = sut.doesDeeplinkMeetTheRequirements(payload)

        assertTrue(result)
    }

    @Test
    fun `EXPECT deeplink does not meet requirements WHEN mnemonic is null`() {
        val payload =
            DeepLinkPayload(
                mnemonic = null,
                rawDeepLinkUri = "algorand://",
            )

        val result = sut.doesDeeplinkMeetTheRequirements(payload)

        assertFalse(result)
    }

    @Test
    fun `EXPECT deeplink does not meet requirements WHEN account address is also provided`() {
        val payload =
            DeepLinkPayload(
                mnemonic = MNEMONIC,
                accountAddress = ADDRESS,
                rawDeepLinkUri = "algorand://$ADDRESS?m=$MNEMONIC",
            )

        val result = sut.doesDeeplinkMeetTheRequirements(payload)

        assertFalse(result)
    }

    @Test
    fun `EXPECT deeplink does not meet requirements WHEN asset id is also provided`() {
        val payload =
            DeepLinkPayload(
                mnemonic = MNEMONIC,
                assetId = ASSET_ID,
                rawDeepLinkUri = "algorand://mnemonic?m=$MNEMONIC&asset=$ASSET_ID",
            )

        val result = sut.doesDeeplinkMeetTheRequirements(payload)

        assertFalse(result)
    }

    @Test
    fun `EXPECT Mnemonic deeplink to be created`() {
        val payload =
            DeepLinkPayload(
                mnemonic = MNEMONIC,
                rawDeepLinkUri = "algorand://mnemonic?m=$MNEMONIC",
            )

        val result = sut.createDeepLink(payload)

        assertTrue(result is DeepLink.Mnemonic)
        assertEquals(MNEMONIC, result.mnemonic)
    }

    private companion object {
        const val MNEMONIC =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        const val ADDRESS = "WOLFYW4VEVVGEGVLQWEL4EMJ5SFCD3UCNKDH2DCUB5HQ6HLM6URZBMPXLI"
        const val ASSET_ID = 226701642L
    }
}
