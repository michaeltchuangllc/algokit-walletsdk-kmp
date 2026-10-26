package com.michaeltchuang.walletsdk.ui.signing.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.michaeltchuang.walletsdk.core.transaction.domain.usecase.SignAlgorandSeedVaultTransaction

@Composable
internal actual fun rememberAlgorandSeedVaultSigner(): SignAlgorandSeedVaultTransaction =
    remember {
        SignAlgorandSeedVaultTransaction { _, _ ->
            error("Algorand Seed Vault signing is available only on Android.")
        }
    }
