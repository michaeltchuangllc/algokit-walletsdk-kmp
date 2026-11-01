package com.michaeltchuang.walletsdk.core.railmpp.data

import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSigner

internal fun interface AlgorandSeedVaultSessionSignerProvider {
    suspend fun get(ownerAddress: String): MppWalletSigner?
}

internal expect fun createAlgorandSeedVaultSessionSignerProvider(): AlgorandSeedVaultSessionSignerProvider
