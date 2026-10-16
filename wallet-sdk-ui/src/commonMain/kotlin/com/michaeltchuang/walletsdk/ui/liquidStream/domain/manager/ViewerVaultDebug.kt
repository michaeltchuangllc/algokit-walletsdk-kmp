package com.michaeltchuang.walletsdk.ui.liquidStream.domain.manager

import io.github.aakira.napier.Napier

internal object ViewerVaultDebug {
    fun log(message: String) {
        Napier.d(message, tag = "LS_VAULT_DEBUG")
    }
}
