package com.michaeltchuang.walletsdk.core.deeplink.builder

import com.michaeltchuang.walletsdk.core.deeplink.model.DeepLink
import com.michaeltchuang.walletsdk.core.deeplink.model.DeepLinkPayload

internal class FidoDeepLinkBuilder : DeepLinkBuilder {
    override fun doesDeeplinkMeetTheRequirements(payload: DeepLinkPayload): Boolean =
        payload.rawDeepLinkUri.lowercase().startsWith("fido:/")

    override fun createDeepLink(payload: DeepLinkPayload): DeepLink = DeepLink.Fido(payload.rawDeepLinkUri)
}
