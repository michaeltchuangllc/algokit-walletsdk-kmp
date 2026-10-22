package com.michaeltchuang.seedvault.sdk.client

import android.content.Intent
import com.michaeltchuang.seedvault.sdk.data.repository.AlgorandRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Immutable payment values reused for both user approval and signing. */
class PreparedAlgorandPayment internal constructor(
    internal val seedId: Long,
    internal val receiver: String,
    internal val amountMicroAlgos: Long,
    internal val paramsJson: String,
    private val client: SeedVaultServiceClient,
) {
    val confirmationIntent: Intent
        get() = client.confirmSignPaymentIntent(seedId, receiver, amountMicroAlgos, paramsJson)
}

/** Builds payment requests and submits them after the host receives successful user approval. */
class AlgorandPaymentClient(
    private val vault: SeedVaultServiceClient,
    private val repository: AlgorandRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun preparePayment(
        seedId: Long,
        receiver: String,
        amountMicroAlgos: Long,
    ): PreparedAlgorandPayment {
        require(receiver.isNotBlank()) { "Receiver is required" }
        require(amountMicroAlgos >= 0) { "Amount cannot be negative" }
        return PreparedAlgorandPayment(
            seedId,
            receiver,
            amountMicroAlgos,
            repository.getPaymentParamsJson(),
            vault,
        )
    }

    /** Call only after RESULT_OK. The service enforces exact, one-time approval of these values. */
    suspend fun signAndSend(payment: PreparedAlgorandPayment): String {
        val signed =
            withContext(ioDispatcher) {
                vault.signAlgorandPayment(
                    payment.seedId,
                    payment.receiver,
                    payment.amountMicroAlgos,
                    payment.paramsJson,
                )
            } ?: error("Seed Vault Service could not sign the payment")
        return repository.sendSignedTransaction(signed)
    }
}
