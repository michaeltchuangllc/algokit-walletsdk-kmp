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

/**
 * Signs [count] payloads with at most one approval: an autosign account is tried first without one, otherwise
 * [approve] runs once and then every payload is signed from that single approval.
 */
internal suspend fun signManyWithSeedVaultApproval(
    count: Int,
    autosign: Boolean,
    approve: suspend () -> Unit,
    sign: suspend (index: Int) -> ByteArray,
): List<ByteArray> {
    if (autosign) {
        try {
            return List(count) { sign(it) }
        } catch (_: SecurityException) {
        }
    }
    approve()
    return List(count) { sign(it) }
}
