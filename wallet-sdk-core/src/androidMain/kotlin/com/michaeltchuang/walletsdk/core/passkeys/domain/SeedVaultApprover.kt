package com.michaeltchuang.walletsdk.core.passkeys.domain

import android.app.Activity
import android.content.Intent

/** What a Seed Vault screen returned. */
class SeedVaultActivityResult(
    val resultCode: Int,
    val data: Intent?,
) {
    val approved: Boolean get() = resultCode == Activity.RESULT_OK
}

/**
 * Starts a Seed Vault screen and returns its result. Set by the screen that is showing (it needs an
 * activity result launcher); null when none is.
 */
fun interface SeedVaultApprover {
    suspend fun launch(intent: Intent): SeedVaultActivityResult
}

/** Holds the [SeedVaultApprover] of the visible passkey screen. */
class SeedVaultApproverHolder {
    @Volatile
    var approver: SeedVaultApprover? = null
}
