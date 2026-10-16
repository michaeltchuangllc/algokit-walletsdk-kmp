package com.michaeltchuang.walletsdk.core.deeplink.parser

import com.michaeltchuang.walletsdk.core.deeplink.model.DeepLinkPayload
import com.michaeltchuang.walletsdk.core.deeplink.parser.query.DeepLinkQueryParser

internal class ParseDeepLinkPayloadImpl(
    private val algorandUriParser: AlgorandUriParser,
    private val accountAddressQueryParser: DeepLinkQueryParser<String?>,
    private val mnemonicQueryParser: DeepLinkQueryParser<String?>,
    private val assetIdQueryParser: DeepLinkQueryParser<Long?>,
) : ParseDeepLinkPayload {
    override fun invoke(url: String): DeepLinkPayload {
        val algorandUri = algorandUriParser.parseUri(url)
        return DeepLinkPayload(
            accountAddress = accountAddressQueryParser.parseQuery(algorandUri),
            assetId = assetIdQueryParser.parseQuery(algorandUri),
            amount = algorandUri.getQueryParam(AMOUNT_QUERY_KEY),
            note = algorandUri.getQueryParam(NOTE_QUERY_KEY),
            xnote = algorandUri.getQueryParam(XNOTE_QUERY_KEY),
            label = algorandUri.getQueryParam(LABEL_QUERY_KEY),
            transactionId = algorandUri.getQueryParam(TRANSACTION_ID_KEY),
            transactionStatus = algorandUri.getQueryParam(TRANSACTION_STATUS_KEY),
            mnemonic = mnemonicQueryParser.parseQuery(algorandUri),
            fee = algorandUri.getQueryParam(FEE_QUERY_KEY),
            votekey = algorandUri.getQueryParam(VOTEKEY_QUERY_KEY),
            selkey = algorandUri.getQueryParam(SELKEY_QUERY_KEY),
            sprfkey = algorandUri.getQueryParam(SPRFKEY_QUERY_KEY),
            votefst = algorandUri.getQueryParam(VOTEFST_QUERY_KEY),
            votelst = algorandUri.getQueryParam(VOTELST_QUERY_KEY),
            votekd = algorandUri.getQueryParam(VOTEKD_QUERY_KEY),
            type = algorandUri.getQueryParam(TYPE_QUERY_KEY),
            path = algorandUri.getQueryParam(PATH_KEY),
            host = algorandUri.host,
            rawDeepLinkUri = url,
        )
    }

    private companion object {
        const val AMOUNT_QUERY_KEY = "amount"
        const val NOTE_QUERY_KEY = "note"
        const val XNOTE_QUERY_KEY = "xnote"
        const val LABEL_QUERY_KEY = "label"
        const val TRANSACTION_ID_KEY = "transactionId"
        const val TRANSACTION_STATUS_KEY = "transactionStatus"
        const val TYPE_QUERY_KEY = "type"
        const val SELKEY_QUERY_KEY = "selkey"
        const val SPRFKEY_QUERY_KEY = "sprfkey"
        const val VOTEFST_QUERY_KEY = "votefst"
        const val VOTELST_QUERY_KEY = "votelst"
        const val VOTEKD_QUERY_KEY = "votekd"
        const val VOTEKEY_QUERY_KEY = "votekey"
        const val FEE_QUERY_KEY = "fee"
        const val PATH_KEY = "path"
    }
}
