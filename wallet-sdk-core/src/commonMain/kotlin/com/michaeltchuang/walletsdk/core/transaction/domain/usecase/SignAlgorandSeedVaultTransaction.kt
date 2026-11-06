package com.michaeltchuang.walletsdk.core.transaction.domain.usecase

fun interface SignAlgorandSeedVaultTransaction {
    suspend operator fun invoke(
        address: String,
        transaction: ByteArray,
    ): ByteArray

    /**
     * Signs several transactions of [address] (e.g. an atomic group). An implementation that can approve them
     * together asks the user once; the default asks once per transaction.
     */
    suspend fun signAll(
        address: String,
        transactions: List<ByteArray>,
    ): List<ByteArray> = transactions.map { invoke(address, it) }
}
