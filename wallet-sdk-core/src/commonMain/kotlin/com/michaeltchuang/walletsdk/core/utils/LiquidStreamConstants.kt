package com.michaeltchuang.walletsdk.core.utils

object LiquidStreamConstants {
    const val DEPOSIT_AMOUNT_MICRO_USDC = 1_000_000L
    const val COST_PER_BLOCK_MICRO_USDC = 100_000L // 0.1 USDC
}

enum class AppId {
    LIQUID_AUTH_STREAM,
    NONE,
}
