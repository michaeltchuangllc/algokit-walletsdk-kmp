package com.michaeltchuang.walletsdk.ui.signing.screens

import androidx.compose.runtime.Composable
import com.michaeltchuang.walletsdk.core.transaction.domain.usecase.SignAlgorandSeedVaultTransaction

@Composable
internal expect fun rememberAlgorandSeedVaultSigner(): SignAlgorandSeedVaultTransaction
