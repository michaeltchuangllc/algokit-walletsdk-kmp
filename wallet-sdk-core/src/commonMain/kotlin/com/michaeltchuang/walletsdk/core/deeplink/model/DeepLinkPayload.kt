package com.michaeltchuang.walletsdk.core.deeplink.model

internal data class DeepLinkPayload(
    val accountAddress: String? = null,
    val walletConnectUrl: String? = null,
    val assetId: Long? = null,
    val amount: String? = null,
    val note: String? = null,
    val xnote: String? = null,
    val mnemonic: String? = null,
    val label: String? = null,
    val transactionStatus: String? = null,
    val transactionId: String? = null,
    val url: String? = null,
    val webImportQrCode: WebImportQrCode? = null,
    val notificationGroupType: NotificationGroupType? = null,
    val fee: String? = null,
    val votekey: String? = null,
    val selkey: String? = null,
    val sprfkey: String? = null,
    val votefst: String? = null,
    val votelst: String? = null,
    val votekd: String? = null,
    val type: String? = null,
    val host: String? = null,
    val path: String? = null,
    val chainId: String? = null,
    val rawDeepLinkUri: String,
)

enum class NotificationGroupType {
    TRANSACTIONS,
    OPT_IN,
    ASSET_INBOX,
    ;

    companion object {
        val DEFAULT = TRANSACTIONS
    }
}

internal data class WebImportQrCode(
    val backupId: String,
    val encryptionKey: String,
)
