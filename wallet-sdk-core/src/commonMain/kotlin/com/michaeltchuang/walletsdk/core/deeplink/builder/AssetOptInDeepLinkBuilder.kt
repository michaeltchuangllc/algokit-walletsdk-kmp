package com.michaeltchuang.walletsdk.core.deeplink.builder

import com.michaeltchuang.walletsdk.core.deeplink.model.DeepLink
import com.michaeltchuang.walletsdk.core.deeplink.model.DeepLinkPayload

internal class AssetOptInDeepLinkBuilder : DeepLinkBuilder {
    /**
     * Handles QR codes for asset opt-in like:
     * - algorand://?amount=0&asset=10458941
     * - algorand://WOLFY...?amount=0&asset=10458941 (with address in path)
     */
    override fun doesDeeplinkMeetTheRequirements(payload: DeepLinkPayload): Boolean =
        with(payload) {
            val hasAssetParam = assetId != null
            val hasZeroAmount = amount == "0"
            val hasNoWalletConnect = walletConnectUrl == null
            val hasNoNote = note == null && xnote == null
            val hasNoUrl = url == null
            val hasNoLabel = label == null
            val hasNoWebImport = webImportQrCode == null
            val hasNoNotification = notificationGroupType == null

            hasAssetParam &&
                hasZeroAmount &&
                hasNoWalletConnect &&
                hasNoNote &&
                hasNoUrl &&
                hasNoLabel &&
                hasNoWebImport &&
                hasNoNotification
        }

    override fun createDeepLink(payload: DeepLinkPayload): DeepLink =
        payload.assetId?.let { safeAssetId ->
            DeepLink.AssetOptIn(
                assetId = safeAssetId,
                amount = payload.amount ?: "0"
            )
        } ?: DeepLink.Undefined(payload.rawDeepLinkUri)
}
