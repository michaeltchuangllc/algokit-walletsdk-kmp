package com.michaeltchuang.walletsdk.core.deeplink.di

import com.michaeltchuang.walletsdk.core.deeplink.DeeplinkHandler
import com.michaeltchuang.walletsdk.core.deeplink.builder.AccountAddressDeepLinkBuilder
import com.michaeltchuang.walletsdk.core.deeplink.builder.AssetOptInDeepLinkBuilder
import com.michaeltchuang.walletsdk.core.deeplink.builder.AssetTransferDeepLinkBuilder
import com.michaeltchuang.walletsdk.core.deeplink.builder.FidoDeepLinkBuilder
import com.michaeltchuang.walletsdk.core.deeplink.builder.KeyRegTransactionDeepLinkBuilder
import com.michaeltchuang.walletsdk.core.deeplink.builder.LiquidAuthDeepLinkBuilder
import com.michaeltchuang.walletsdk.core.deeplink.builder.MnemonicDeepLinkBuilder
import com.michaeltchuang.walletsdk.core.deeplink.parser.AlgorandUriParser
import com.michaeltchuang.walletsdk.core.deeplink.parser.AlgorandUriParserImpl
import com.michaeltchuang.walletsdk.core.deeplink.parser.CreateDeepLink
import com.michaeltchuang.walletsdk.core.deeplink.parser.CreateDeepLinkImpl
import com.michaeltchuang.walletsdk.core.deeplink.parser.ParseDeepLinkPayload
import com.michaeltchuang.walletsdk.core.deeplink.parser.ParseDeepLinkPayloadImpl
import com.michaeltchuang.walletsdk.core.deeplink.parser.query.AccountAddressQueryParser
import com.michaeltchuang.walletsdk.core.deeplink.parser.query.AssetIdQueryParser
import com.michaeltchuang.walletsdk.core.deeplink.parser.query.MnemonicQueryParser
import org.koin.dsl.module

val deepLinkModules =
    listOf(
        module {

            // Provide AlgorandUriParser
            single<AlgorandUriParser> { AlgorandUriParserImpl() }

            // Provide ParseDeepLinkPayload
            single<ParseDeepLinkPayload> {
                ParseDeepLinkPayloadImpl(
                    algorandUriParser = get(),
                    mnemonicQueryParser = MnemonicQueryParser(get()),
                    accountAddressQueryParser = AccountAddressQueryParser(),
                    assetIdQueryParser = AssetIdQueryParser(),
                )
            }

            // Provide CreateDeepLink
            single<CreateDeepLink> {
                CreateDeepLinkImpl(
                    parseDeepLinkPayload = get(),
                    mnemonicDeepLinkBuilder = MnemonicDeepLinkBuilder(),
                    keyRegTransactionDeepLinkBuilder = KeyRegTransactionDeepLinkBuilder(),
                    assetOptInDeepLinkBuilder = AssetOptInDeepLinkBuilder(),
                    assetTransferDeepLinkBuilder = AssetTransferDeepLinkBuilder(),
                    accountAddressDeepLinkBuilder = AccountAddressDeepLinkBuilder(),
                    fidoDeepLinkBuilder = FidoDeepLinkBuilder(),
                    liquidAuthDeepLinkBuilder = LiquidAuthDeepLinkBuilder(),
                )
            }

            single { DeeplinkHandler(get()) }
        },
    )
