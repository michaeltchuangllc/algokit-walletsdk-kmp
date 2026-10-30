package com.michaeltchuang.walletsdk.core.deeplink.builder

import com.michaeltchuang.walletsdk.core.deeplink.model.DeepLink
import com.michaeltchuang.walletsdk.core.deeplink.model.DeepLinkPayload
import com.michaeltchuang.walletsdk.core.deeplink.utils.AssetConstants.ALGO_ID

internal class AssetTransferDeepLinkBuilder : DeepLinkBuilder {
    override fun doesDeeplinkMeetTheRequirements(payload: DeepLinkPayload): Boolean =
        with(payload) {
            val hasValidAssetQueries = amount != null || assetId != null
            val hasValidTransferQueries =
                accountAddress != null &&
                    walletConnectUrl == null &&
                    webImportQrCode == null &&
                    notificationGroupType == null

            hasValidAssetQueries && hasValidTransferQueries
        }

    override fun createDeepLink(payload: DeepLinkPayload): DeepLink =
        DeepLink.AssetTransfer(
            receiverAccountAddress = payload.accountAddress.orEmpty(),
            amount = payload.amount ?: "0",
            note = payload.note,
            xnote = payload.xnote,
            assetId = payload.assetId ?: ALGO_ID,
            label = payload.label,
            chainId = payload.chainId,
        )
}
