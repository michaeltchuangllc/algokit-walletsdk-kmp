package com.michaeltchuang.walletsdk.seedvault.service.ui.preview

import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants
import com.michaeltchuang.walletsdk.seedvault.service.domain.model.AuthorizedApp
import com.michaeltchuang.walletsdk.seedvault.service.domain.model.VaultSeed

/** Fake data for the Compose previews and unit tests. */
object SeedVaultPreviewData {
    /** A valid 25-word mnemonic, the same length as a Falcon-1024 seed phrase. */
    val seedPhrase = WalletSdkConstants.SAMPLE_ALGO25_MNEMONIC.split(" ")

    private val walletDemoApp =
        AuthorizedApp(
            packageName = "com.michaeltchuang.walletsdk.demo",
            label = "AlgoKit Wallet SDK Demo",
        )
    private val walletServiceDemoApp =
        AuthorizedApp(
            packageName = "com.michaeltchuang.walletsdk.service.demo",
            label = "Wallet Service Demo",
        )
    private val systemWalletApp =
        AuthorizedApp(
            packageName = "com.example.systemwallet",
            label = "System Wallet",
            isPrivileged = true,
        )

    val seeds =
        listOf(
            VaultSeed(
                id = 1,
                name = "Demo Seed 1",
                isBackedUp = true,
                authorizedApps = listOf(walletDemoApp),
            ),
            VaultSeed(
                id = 2,
                name = "Demo Seed 2",
                isBackedUp = false,
                authorizedApps = listOf(walletDemoApp, walletServiceDemoApp, systemWalletApp),
            ),
        )
}
