package com.michaeltchuang.walletsdk.ui.settings.utils

import androidx.compose.runtime.Composable

expect val isSeedVaultTransactionSigningSupported: Boolean

@Composable
expect fun rememberSeedVaultTransactionSigner(): suspend (address: String, message: ByteArray) -> ByteArray
