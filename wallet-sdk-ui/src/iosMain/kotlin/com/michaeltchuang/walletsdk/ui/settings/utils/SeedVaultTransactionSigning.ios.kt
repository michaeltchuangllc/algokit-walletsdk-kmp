package com.michaeltchuang.walletsdk.ui.settings.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

actual val isSeedVaultTransactionSigningSupported: Boolean = false

@Composable
actual fun rememberSeedVaultTransactionSigner(): suspend (address: String, message: ByteArray) -> ByteArray =
    remember {
        { _: String, _: ByteArray ->
            throw UnsupportedOperationException("Seed Vault transaction signing is not supported on iOS")
        }
    }
