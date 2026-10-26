package com.michaeltchuang.walletsdk.ui.signing.screens

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.michaeltchuang.seedvault.sdk.client.SeedVaultServiceClient
import com.michaeltchuang.walletsdk.core.transaction.domain.usecase.SignAlgorandSeedVaultTransaction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private class ApprovalState {
    var pending: CompletableDeferred<Boolean>? = null
    var active = true
}

@Composable
internal actual fun rememberAlgorandSeedVaultSigner(): SignAlgorandSeedVaultTransaction {
    val context = LocalContext.current.applicationContext
    val approval = remember { ApprovalState() }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            approval.pending?.complete(result.resultCode == Activity.RESULT_OK)
            approval.pending = null
        }
    DisposableEffect(approval) {
        approval.active = true
        onDispose {
            approval.active = false
            approval.pending?.complete(false)
            approval.pending = null
        }
    }
    return remember(context, launcher, approval) {
        SignAlgorandSeedVaultTransaction { address, transaction ->
            withContext(Dispatchers.Main.immediate) {
                check(approval.active && approval.pending == null) { "Seed Vault approval is unavailable or already pending." }
                val result = CompletableDeferred<Boolean>()
                approval.pending = result
                val bytes = transaction.copyOf()
                val client = SeedVaultServiceClient(context)
                var launched = false
                try {
                    checkNotNull(withTimeoutOrNull(10_000) { client.bindAsync() }) {
                        "Timed out connecting to Seed Vault."
                    }
                    val account =
                        withContext(Dispatchers.IO) {
                            client.getAuthorizedSeeds().flatMap { it.accounts }.firstOrNull { it.address == address }
                        }
                    checkNotNull(account) { "This account is no longer authorized in Seed Vault." }
                    val signed =
                        signWithSeedVaultApproval(
                            autosign = account.isAutosign,
                            approve = {
                                ensureActive()
                                check(approval.active) { "Transaction screen was closed." }
                                launcher.launch(client.confirmSignTransactionIntent(address, bytes))
                                launched = true
                                check(result.await()) { "Seed Vault signing was cancelled." }
                            },
                            sign = {
                                ensureActive()
                                check(approval.active) { "Transaction screen was closed." }
                                withContext(Dispatchers.IO) {
                                    checkNotNull(client.signTransaction(address, bytes)) { "Seed Vault could not sign the transaction." }
                                }
                            },
                        )
                    ensureActive()
                    check(approval.active) { "Transaction screen was closed." }
                    signed
                } finally {
                    if (!launched && approval.pending === result) approval.pending = null
                    client.unbind()
                }
            }
        }
    }
}
