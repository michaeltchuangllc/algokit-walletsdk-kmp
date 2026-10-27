package com.michaeltchuang.walletsdk.core.transaction.domain.usecase

fun interface SignAlgorandSeedVaultTransaction {
    suspend operator fun invoke(
        address: String,
        transaction: ByteArray,
    ): ByteArray
}
