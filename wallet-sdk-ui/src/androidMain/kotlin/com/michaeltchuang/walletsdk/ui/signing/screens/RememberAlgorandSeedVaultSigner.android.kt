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

private object SeedVaultApprovalCoordinator {
    @Volatile
    var pending: CompletableDeferred<Boolean>? = null

    @Volatile
    var active = true

    fun start(): CompletableDeferred<Boolean> {
        check(active) { "Seed Vault approval is unavailable or already pending." }
        check(pending == null) { "Seed Vault approval is unavailable or already pending." }
        val result = CompletableDeferred<Boolean>()
        pending = result
        return result
    }

    fun complete(result: Boolean) {
        val current = pending
        pending = null
        current?.complete(result)
    }

    fun release() {
        val current = pending
        pending = null
        current?.complete(false)
    }
}

private class AlgorandSeedVaultSigning(
    val signAll: suspend (String, List<ByteArray>) -> List<ByteArray>,
) {
    suspend fun sign(
        address: String,
        data: ByteArray,
    ): ByteArray = signAll(address, listOf(data)).single()
}

@Composable
internal actual fun rememberAlgorandSeedVaultSigner(): SignAlgorandSeedVaultTransaction {
    val signing = rememberAlgorandSeedVaultSigning(message = false)
    return remember(signing) {
        object : SignAlgorandSeedVaultTransaction {
            override suspend fun invoke(
                address: String,
                transaction: ByteArray,
            ): ByteArray = signing.sign(address, transaction)

            // One Seed Vault approval for the whole group
            override suspend fun signAll(
                address: String,
                transactions: List<ByteArray>,
            ): List<ByteArray> = signing.signAll(address, transactions)
        }
    }
}

@Composable
internal actual fun rememberAlgorandSeedVaultMessageSigner(): suspend (String, ByteArray) -> ByteArray {
    val signing = rememberAlgorandSeedVaultSigning(message = true)
    return remember(signing) { { address, data -> signing.sign(address, data) } }
}

@Composable
private fun rememberAlgorandSeedVaultSigning(message: Boolean): AlgorandSeedVaultSigning {
    val context = LocalContext.current.applicationContext
    val approval = remember { SeedVaultApprovalCoordinator }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            approval.complete(result.resultCode == Activity.RESULT_OK)
        }
    DisposableEffect(approval) {
        approval.active = true
        onDispose {
            approval.active = false
            approval.release()
        }
    }
    return remember(context, launcher, approval, message) {
        AlgorandSeedVaultSigning { address, items ->
            require(items.isNotEmpty()) { "Nothing to sign." }
            require(!message || items.size == 1) { "Seed Vault signs one message at a time." }
            withContext(Dispatchers.Main.immediate) {
                val result = approval.start()
                val payloads = items.map { it.copyOf() }
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
                        signManyWithSeedVaultApproval(
                            count = payloads.size,
                            autosign = account.isAutosign && !account.requiresApproval,
                            approve = {
                                ensureActive()
                                check(approval.active) { "Transaction screen was closed." }
                                launcher.launch(
                                    when {
                                        message -> client.confirmSignBytesIntent(address, payloads.single())
                                        payloads.size == 1 -> client.confirmSignTransactionIntent(address, payloads.single())
                                        // Several transactions: one screen and one fingerprint for all of them
                                        else -> client.confirmSignTransactionsIntent(address, payloads)
                                    },
                                )
                                launched = true
                                check(result.await()) { "Seed Vault signing was cancelled." }
                            },
                            sign = { index ->
                                ensureActive()
                                check(approval.active) { "Transaction screen was closed." }
                                withContext(Dispatchers.IO) {
                                    checkNotNull(
                                        if (message) {
                                            client.signBytes(address, payloads[index])
                                        } else {
                                            client.signTransaction(address, payloads[index])
                                        },
                                    ) { "Seed Vault signing failed." }
                                }
                            },
                        )
                    ensureActive()
                    check(approval.active) { "Transaction screen was closed." }
                    signed
                } finally {
                    if (!launched && approval.pending === result) {
                        approval.complete(false)
                    }
                    client.unbind()
                }
            }
        }
    }
}
