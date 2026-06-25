package com.michaeltchuang.walletsdk.core.deeplink.utils

object AssetConstants {
    const val ALGO_ID = -7L
    const val DEFAULT_ASSET_DECIMAL = 0
    const val ALGO_FULL_NAME = "Algo"
    const val ALGO_SHORT_NAME = "ALGO"
    const val ALGO_DECIMALS = 6
    const val ALGORAND_DISCORD_URL = "https://discord.com/invite/84AActu3at"
    const val ALGORAND_TELEGRAM_URL = "https://t.me/algorand"
    const val ALGORAND_TWITTER_USERNAME = "Algorand"
    const val ALGORAND_WEBSITE_URL = "https://algorand.co"
    const val MINIMUM_CURRENCY_VALUE_TO_DISPLAY_EXACT_AMOUNT = "0.000001"
    // val ALGO_TOTAL_SUPPLY: BigDecimal = "10000000000".toBigDecimal()

    const val USDC_MAINNET_ID = 31566704L
    const val USDC_TESTNET_ID = 10458941L
    const val USDT_MAINNET_ID = 312769L

    fun usdcIdForNetwork(network: String): Long =
        if (network.contains("mainnet", ignoreCase = true)) {
            USDC_MAINNET_ID
        } else {
            USDC_TESTNET_ID
        }
}
