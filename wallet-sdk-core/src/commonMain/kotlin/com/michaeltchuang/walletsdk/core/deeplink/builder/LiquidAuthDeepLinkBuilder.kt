package com.michaeltchuang.walletsdk.core.deeplink.builder

import com.michaeltchuang.walletsdk.core.deeplink.model.DeepLink
import com.michaeltchuang.walletsdk.core.deeplink.model.DeepLinkPayload

internal class LiquidAuthDeepLinkBuilder : DeepLinkBuilder {
    override fun doesDeeplinkMeetTheRequirements(payload: DeepLinkPayload): Boolean =
        payload.rawDeepLinkUri.lowercase().startsWith("liquid:/")

    override fun createDeepLink(payload: DeepLinkPayload): DeepLink = DeepLink.LiquidAuth(payload.rawDeepLinkUri)
}
