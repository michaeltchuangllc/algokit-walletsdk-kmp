package com.michaeltchuang.walletsdk.core.account.domain.model.local

internal data class ActiveHdAccount(
    val accountIndex: Int,
    val entropy: ByteArray,
    val firstBatchHdAccountAddress: List<HdAccountAddress>,
) {
    data class HdAccountAddress(
        val address: String,
        val accountIndex: Int,
        val changeIndex: Int,
        val keyIndex: Int,
        val fastLookup: AccountFastLookup?,
    )
}
