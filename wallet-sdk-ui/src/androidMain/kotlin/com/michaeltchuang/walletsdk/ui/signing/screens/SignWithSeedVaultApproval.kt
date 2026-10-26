package com.michaeltchuang.walletsdk.ui.signing.screens

internal suspend fun signWithSeedVaultApproval(
    autosign: Boolean,
    approve: suspend () -> Unit,
    sign: suspend () -> ByteArray,
): ByteArray {
    if (autosign) {
        try {
            return sign()
        } catch (_: SecurityException) {
        }
    }
    approve()
    return sign()
}
