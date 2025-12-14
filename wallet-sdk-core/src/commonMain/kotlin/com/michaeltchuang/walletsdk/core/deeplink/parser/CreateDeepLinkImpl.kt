package com.michaeltchuang.walletsdk.core.deeplink.parser

import com.michaeltchuang.walletsdk.core.deeplink.builder.AccountAddressDeepLinkBuilder
import com.michaeltchuang.walletsdk.core.deeplink.builder.AssetTransferDeepLinkBuilder
import com.michaeltchuang.walletsdk.core.deeplink.builder.FidoDeepLinkBuilder
import com.michaeltchuang.walletsdk.core.deeplink.builder.KeyRegTransactionDeepLinkBuilder
import com.michaeltchuang.walletsdk.core.deeplink.builder.MnemonicDeepLinkBuilder
import com.michaeltchuang.walletsdk.core.deeplink.model.DeepLink

internal class CreateDeepLinkImpl(
    private val parseDeepLinkPayload: ParseDeepLinkPayload,
    private val mnemonicDeepLinkBuilder: MnemonicDeepLinkBuilder,
    private val keyRegTransactionDeepLinkBuilder: KeyRegTransactionDeepLinkBuilder,
    private val assetTransferDeepLinkBuilder: AssetTransferDeepLinkBuilder,
    private val accountAddressDeepLinkBuilder: AccountAddressDeepLinkBuilder,
    private val fidoDeepLinkBuilder: FidoDeepLinkBuilder
) : CreateDeepLink {
    override fun invoke(url: String): DeepLink {
        val payload = parseDeepLinkPayload(url)

        return when {
            mnemonicDeepLinkBuilder.doesDeeplinkMeetTheRequirements(payload) -> {
                mnemonicDeepLinkBuilder.createDeepLink(payload)
            }
            keyRegTransactionDeepLinkBuilder.doesDeeplinkMeetTheRequirements(payload) -> {
                keyRegTransactionDeepLinkBuilder.createDeepLink(payload)
            }
            assetTransferDeepLinkBuilder.doesDeeplinkMeetTheRequirements(payload) -> {
                assetTransferDeepLinkBuilder.createDeepLink(payload)
            }
            accountAddressDeepLinkBuilder.doesDeeplinkMeetTheRequirements(payload) -> {
                accountAddressDeepLinkBuilder.createDeepLink(payload)
            }
            fidoDeepLinkBuilder.doesDeeplinkMeetTheRequirements(payload) -> {
                fidoDeepLinkBuilder.createDeepLink(payload)
            }
            else -> DeepLink.Undefined(url)
        }
    }
}
