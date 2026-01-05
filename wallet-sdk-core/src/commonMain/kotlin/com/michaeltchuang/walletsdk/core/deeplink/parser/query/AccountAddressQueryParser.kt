package com.michaeltchuang.walletsdk.core.deeplink.parser.query

import com.michaeltchuang.walletsdk.core.algosdk.isValidAlgorandAddress
import com.michaeltchuang.walletsdk.core.deeplink.model.AlgorandUri

internal class AccountAddressQueryParser : DeepLinkQueryParser<String?> {
    override fun parseQuery(algorandUri: AlgorandUri): String? =
        when {
            algorandUri.isAppLink() -> getAddressFromAppLink(algorandUri)
//            isCoinbaseDeepLink(algorandUri.rawUri) -> getCoinbaseAddress(algorandUri.rawUri)
            else -> algorandUri.getQueryParam(ACCOUNT_ID_QUERY_KEY) ?: algorandUri.host
        }?.takeIf { isValidAlgorandAddress(it) } ?: algorandUri.rawUri.takeIf { isValidAlgorandAddress(it) }

    private fun getAddressFromAppLink(uri: AlgorandUri): String? =
        uri.path
            ?.split("/")
            ?.firstOrNull { isValidAlgorandAddress(it) }
            ?: uri.getQueryParam(ACCOUNT_ID_QUERY_KEY)

    private companion object {
        const val ACCOUNT_ID_QUERY_KEY = "account"
    }
}
