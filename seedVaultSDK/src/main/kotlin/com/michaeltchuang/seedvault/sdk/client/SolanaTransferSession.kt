package com.michaeltchuang.seedvault.sdk.client

import android.net.Uri
import com.michaeltchuang.seedvault.sdk.data.repository.SolanaTransferRepository
import com.michaeltchuang.seedvault.sdk.domain.model.SolanaSeed
import java.util.Base64

/** Public data needed by the host to request a vault signature, never a private key. */
data class PreparedSolanaTransfer(
    val messageBase64: String,
    val authToken: Long,
    val derivationPath: Uri,
)

/**
 * Owns one pending transfer. The host launches the signing intent and supplies the resulting signature.
 * Clear on cancellation or a network change; a submission consumes the pending message.
 */
class SolanaTransferSession(
    private val repository: SolanaTransferRepository,
) {
    private val lock = Any()
    private var generation = 0L
    private var pendingMessage: ByteArray? = null
    private var pendingEndpoint: String? = null

    suspend fun prepare(
        seeds: List<SolanaSeed>,
        fromPublicKey: String,
        toPublicKey: String,
        amountSol: Double,
    ): PreparedSolanaTransfer {
        val requestGeneration =
            synchronized(lock) {
                check(pendingMessage == null) { "A transfer is already awaiting a signature" }
                ++generation
            }
        repository.solToLamports(amountSol)
        val (seed, account) =
            seeds
                .flatMap { seed -> seed.accounts.map { seed to it } }
                .firstOrNull { (_, account) -> account.publicKeyEncoded == fromPublicKey }
                ?: error("Could not find an authorized signing account")
        val endpoint = repository.getRpcEndpoint()
        val message =
            repository.createTransferTransactionData(fromPublicKey, toPublicKey, amountSol)?.second
                ?: error("Failed to create transfer transaction")
        synchronized(lock) {
            check(generation == requestGeneration) { "Transfer preparation was cancelled" }
            check(endpoint == repository.getRpcEndpoint()) { "Network changed during transfer preparation" }
            pendingMessage = message.copyOf()
            pendingEndpoint = endpoint
        }
        return PreparedSolanaTransfer(Base64.getEncoder().encodeToString(message), seed.authToken, account.derivationPath)
    }

    suspend fun submit(signatureBase64: String): SolanaTransferRepository.TransferResult {
        val signature = Base64.getMimeDecoder().decode(signatureBase64)
        require(signature.size == 64) { "Expected a 64-byte Solana signature" }
        val message =
            synchronized(lock) {
                val message = checkNotNull(pendingMessage) { "No pending transaction to sign" }
                check(pendingEndpoint == repository.getRpcEndpoint()) { "Network changed after signing was requested" }
                pendingMessage = null
                pendingEndpoint = null
                generation++
                message
            }
        return repository.sendSignedTransaction(repository.serializeSignedTransaction(message, signature))
    }

    fun clear() {
        synchronized(lock) {
            pendingMessage = null
            pendingEndpoint = null
            generation++
        }
    }
}
