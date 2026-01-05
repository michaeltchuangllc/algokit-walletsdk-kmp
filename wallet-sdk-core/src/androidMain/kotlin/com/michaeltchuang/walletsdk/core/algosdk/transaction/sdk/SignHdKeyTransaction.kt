package com.michaeltchuang.walletsdk.core.algosdk.transaction.sdk

interface SignHdKeyTransaction {
    fun signTransaction(
        transactionByteArray: ByteArray,
        seed: ByteArray,
        account: Int,
        change: Int,
        key: Int,
    ): ByteArray?

    fun signLegacyArbitraryData(
        transactionByteArray: ByteArray,
        seed: ByteArray,
        account: Int,
        change: Int,
        key: Int,
    ): ByteArray?

    fun signArbitraryData(
        data: ByteArray,
        seed: ByteArray,
        account: Int,
        change: Int,
        key: Int,
    ): ByteArray?
}
