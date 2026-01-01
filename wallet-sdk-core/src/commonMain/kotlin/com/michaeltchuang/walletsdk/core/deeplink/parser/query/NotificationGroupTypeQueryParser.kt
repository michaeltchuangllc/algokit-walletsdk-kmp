package com.michaeltchuang.walletsdk.core.deeplink.parser.query

import com.michaeltchuang.walletsdk.core.deeplink.model.AlgorandUri
import com.michaeltchuang.walletsdk.core.deeplink.model.NotificationGroupType

internal class NotificationGroupTypeQueryParser : DeepLinkQueryParser<NotificationGroupType?> {
    override fun parseQuery(algorandUri: AlgorandUri): NotificationGroupType? =
        when (getNotificationGroupQueryOrNull(algorandUri)) {
            NOTIFICATION_ACTION_ASSET_TRANSACTIONS -> NotificationGroupType.TRANSACTIONS
            NOTIFICATION_ACTION_ASSET_OPTIN -> NotificationGroupType.OPT_IN
            NOTIFICATION_ASSET_INBOX -> NotificationGroupType.ASSET_INBOX
            else -> null
        }

    private fun getNotificationGroupQueryOrNull(algorandUri: AlgorandUri): String {
        val builder = StringBuilder(algorandUri.host.orEmpty())
        if (!algorandUri.path.isNullOrBlank()) {
            builder.append("/").append(algorandUri.path)
        }
        return builder.toString()
    }

    private companion object {
        const val NOTIFICATION_ACTION_ASSET_TRANSACTIONS = "asset/transactions"
        const val NOTIFICATION_ACTION_ASSET_OPTIN = "asset/opt-in"
        const val NOTIFICATION_ASSET_INBOX = "asset-inbox"
    }
}
