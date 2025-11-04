package com.michaeltchuang.walletsdk.core.account.domain.usecase.core

import com.michaeltchuang.walletsdk.core.foundation.WalletSdkResult

fun interface AddAlgo25Account {
    suspend operator fun invoke(
        address: String,
        secretKey: ByteArray,
        isBackedUp: Boolean,
        customName: String?,
        orderIndex: Int,
    )
}

fun interface AddHdSeed {
    suspend operator fun invoke(entropy: ByteArray): WalletSdkResult<Int>
}

fun interface AddFalcon24Account {
    suspend operator fun invoke(
        address: String,
        publicKey: ByteArray,
        privateKey: ByteArray,
        seedId: Int,
        isBackedUp: Boolean,
        customName: String?,
        orderIndex: Int,
    )
}

fun interface AddHdKeyAccount {
    suspend operator fun invoke(
        address: String,
        publicKey: ByteArray,
        privateKey: ByteArray,
        seedId: Int,
        account: Int,
        change: Int,
        keyIndex: Int,
        derivationType: Int,
        isBackedUp: Boolean,
        customName: String?,
        orderIndex: Int,
    )
}
