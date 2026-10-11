package com.michaeltchuang.walletsdk.ui.settings.utils

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import com.michaeltchuang.walletsdk.core.account.domain.repository.solana.SeedVaultRepository
import com.michaeltchuang.walletsdk.core.solana.utils.Base58
import com.solanamobile.seedvault.SigningRequest
import com.solanamobile.seedvault.Wallet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.koin.compose.koinInject

// The UI module's minSdk (34) is above Seed Vault's minimum API (30).
actual val isSeedVaultTransactionSigningSupported: Boolean = true

@Composable
actual fun rememberSeedVaultTransactionSigner(): suspend (address: String, message: ByteArray) -> ByteArray {
    val context = LocalContext.current
    val repository = koinInject<SeedVaultRepository>()
    // The ActivityResultRegistry also restores its launch key. Reserve that key after recreation
    // until the old result arrives, even though its original coroutine can no longer be resumed.
    val launchOutstanding = rememberSaveable { mutableStateOf(false) }
    val state = remember { SeedVaultSigningState(launchOutstanding) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            state.onResult(result)
        }

    DisposableEffect(state) {
        onDispose { state.dispose() }
    }

    return remember(context, repository, launcher, state) {
        { address: String, message: ByteArray ->
            // Snapshot before any dispatcher hop or repository suspension.
            val messageSnapshot = message.copyOf()
            state.sign(address, messageSnapshot, repository) { authToken, path ->
                launcher.launch(
                    Wallet.signTransactions(
                        context,
                        authToken,
                        arrayListOf(SigningRequest(messageSnapshot, listOf(path))),
                    ),
                )
            }
        }
    }
}

/** All mutable state is confined to the main dispatcher, including activity results and disposal. */
private class SeedVaultSigningState(
    private val launchOutstanding: MutableState<Boolean>,
) {
    private var disposed = false
    private var operation: Job? = null
    private var pending: PendingSignature? = null

    suspend fun sign(
        address: String,
        message: ByteArray,
        repository: SeedVaultRepository,
        launch: (authToken: Long, path: Uri) -> Unit,
    ): ByteArray =
        withContext(Dispatchers.Main.immediate) {
            // A dedicated child scope lets disposal cancel this operation without canceling the
            // caller's entire (potentially ViewModel-owned) scope.
            coroutineScope {
                if (disposed) throw CancellationException("Seed Vault signer was disposed")
                check(operation == null && !launchOutstanding.value) {
                    "A Seed Vault signing request is already in flight"
                }
                operation = currentCoroutineContext().job
                var request: PendingSignature? = null
                try {
                    require(message.isNotEmpty()) { "Solana transaction message must not be empty" }
                    require(address.length in 32..44) { "Invalid Solana address length" }
                    val publicKey = Base58.decode(address)
                    require(publicKey.size == 32) { "Solana address must encode a 32-byte public key" }

                    val account =
                        repository
                            .getSolanaSeeds()
                            .firstNotNullOfOrNull { seed ->
                                seed.accounts.firstOrNull { it.address == address }?.let { seed.authToken to it }
                            } ?: error("No authorized Seed Vault account matches the requested address")
                    currentCoroutineContext().ensureActive()
                    require(account.second.derivationPath.isNotBlank()) {
                        "Seed Vault account has no derivation path"
                    }
                    val path = account.second.derivationPath.toUri()
                    request = PendingSignature(publicKey, message)
                    pending = request
                    launchOutstanding.value = true
                    try {
                        launch(account.first, path)
                    } catch (exception: Exception) {
                        launchOutstanding.value = false
                        pending = null
                        throw exception
                    }
                    request.result.await()
                } finally {
                    operation = null
                    request?.result?.cancel()
                    // Do not free a launched activity's slot on caller cancellation: its late
                    // callback has no request ID and must never complete a subsequent request.
                    if (!launchOutstanding.value) pending = null
                }
            }
        }

    fun onResult(result: ActivityResult) {
        val request = pending
        pending = null
        launchOutstanding.value = false
        if (disposed || request == null || !request.result.isActive) return

        if (result.resultCode == Activity.RESULT_CANCELED) {
            request.result.cancel(CancellationException("Seed Vault signing was rejected or canceled"))
            return
        }
        try {
            request.result.complete(verifiedSignature(request, result.resultCode, result.data))
        } catch (exception: Exception) {
            request.result.completeExceptionally(exception)
        }
    }

    fun dispose() {
        disposed = true
        val cancellation = CancellationException("Seed Vault signer left composition")
        operation?.cancel(cancellation)
        pending?.result?.cancel(cancellation)
        pending = null
    }

    private fun verifiedSignature(
        request: PendingSignature,
        resultCode: Int,
        data: Intent?,
    ): ByteArray {
        val responses = Wallet.onSignTransactionsResult(resultCode, data)
        check(responses.size == 1) { "Seed Vault must return exactly one signing response" }
        val response = responses.single()
        check(response.signatures.size == 1 && response.resolvedDerivationPaths.size == 1) {
            "Seed Vault must return exactly one signature and resolved derivation path"
        }
        val signature = response.signatures.single().copyOf()
        check(signature.size == 64) { "Seed Vault did not return a detached Ed25519 signature" }
        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(request.publicKey, 0))
        verifier.update(request.message, 0, request.message.size)
        check(verifier.verifySignature(signature)) {
            "Seed Vault signature does not match the requested address and transaction message"
        }
        return signature
    }
}

private class PendingSignature(
    val publicKey: ByteArray,
    val message: ByteArray,
) {
    val result = CompletableDeferred<ByteArray>()
}
