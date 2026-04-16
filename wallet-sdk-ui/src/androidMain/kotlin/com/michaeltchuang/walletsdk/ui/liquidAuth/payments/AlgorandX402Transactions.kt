package com.michaeltchuang.walletsdk.ui.liquidAuth.payments

import android.util.Log
import com.algorand.algosdk.transaction.SignedTransaction
import com.algorand.algosdk.util.Encoder
import com.michaeltchuang.walletsdk.core.algosdk.makeAssetTransferTxn
import com.michaeltchuang.walletsdk.core.algosdk.signAlgo25Transaction
import com.michaeltchuang.walletsdk.core.foundation.utils.SuggestedParams
import com.michaeltchuang.walletsdk.ui.liquidAuth.model.X402PaymentMessages

/**
 * X402 Payment Helper for Liquid Auth
 *
 * Uses existing AlgoAccount SDK functions for transaction creation/signing.
 *
 * Payment flow:
 * 1. Client creates 1 ALGO payment txn (to creator)
 * 2. Client signs with secret key
 * 3. Client sends signed txn to creator via WebRTC
 * 4. Creator verifies and submits
 * 5. Every 3 seconds: deduct 0.1 ALGO from balance
 * 6. When depleted: creator claims remaining funds
 */
object AlgorandX402Payments {
    private const val TAG = "X402Payments"
    private const val DEPOSIT_MICRO_ALGOS = "1000000" // 1 ALGO
    private const val COST_PER_BLOCK_MICRO_ALGOS = 100_000L // 0.1 ALGO

    /**
     * Create X402 deposit payment transaction (unsigned)
     *
     * @param senderAddress Client address
     * @param creatorAddress Creator/receiver address
     * @param sessionId Payment session ID for note
     * @param suggestedParams Network transaction params
     * @return Unsigned transaction bytes
     */
    fun createDepositTransaction(
        senderAddress: String,
        creatorAddress: String,
        sessionId: String,
        suggestedParams: SuggestedParams,
    ): ByteArray {
        val note = "X402:$sessionId".toByteArray()
        return makeAssetTransferTxn(
            senderAddress = senderAddress,
            receiverAddress = creatorAddress,
            amount = DEPOSIT_MICRO_ALGOS,
            assetId = 10458941,
            noteInByteArray = note,
            suggestedParams = suggestedParams,
        )
    }

    /**
     * Sign a transaction with Algo25 secret key
     *
     * @param transactionBytes Unsigned transaction bytes
     * @param secretKey Algo25 secret key (from secure storage)
     * @return Signed transaction bytes ready for submission
     */
    fun signTransaction(
        transactionBytes: ByteArray,
        secretKey: ByteArray,
    ): ByteArray = signAlgo25Transaction(secretKey, transactionBytes)

    /**
     * Decode signed transaction bytes for verification
     */
    fun decodeSignedTransaction(bytes: ByteArray): SignedTransaction? =
        try {
            Encoder.decodeFromMsgPack(bytes, SignedTransaction::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode signed transaction", e)
            null
        }

    /**
     * Verify payment proof from client
     *
     * @param signedTxnBytes Signed transaction from client
     * @param expectedCreator Expected receiver address
     * @param expectedSessionId Expected session ID in note
     * @return Verification result
     */
    fun verifyPaymentProof(
        signedTxnBytes: ByteArray,
        expectedCreator: String,
        expectedSessionId: String,
    ): PaymentVerificationResult {
        val signedTxn =
            decodeSignedTransaction(signedTxnBytes)
                ?: return PaymentVerificationResult.Invalid("Failed to decode transaction")

        val txn = signedTxn.tx

        // Verify amount is 1 ALGO
        if (txn.amount.toString() != DEPOSIT_MICRO_ALGOS) {
            return PaymentVerificationResult.Invalid(
                "Incorrect amount: expected 1 ALGO, got ${txn.amount.toDouble() / 1_000_000.0} ALGO",
            )
        }

        // Verify receiver
        if (txn.receiver.toString() != expectedCreator) {
            return PaymentVerificationResult.Invalid(
                "Incorrect receiver: expected $expectedCreator, got ${txn.receiver}",
            )
        }

        // Verify note contains session ID
        val note = txn.note?.let { String(it) } ?: ""
        if (!note.contains(expectedSessionId)) {
            return PaymentVerificationResult.Invalid("Session ID not found in note: $note")
        }

        // Note: Signature verification happens when submitting to network
        // We just verify the transaction structure and content here

        return PaymentVerificationResult.Valid(
            senderAddress = txn.sender.toString(),
            signedTransactionBytes = signedTxnBytes,
            transactionId = txn.txID(),
        )
    }

    /**
     * Create balance update message for client
     */
    fun createBalanceUpdate(
        sessionId: String,
        blocksConsumed: Int,
    ): X402PaymentMessages.BalanceUpdate {
        val consumed = blocksConsumed * COST_PER_BLOCK_MICRO_ALGOS
        val remaining = 1_000_000L - consumed

        return X402PaymentMessages.BalanceUpdate(
            id = sessionId,
            initialDepositMicroAlgos = 1_000_000L,
            consumedMicroAlgos = consumed,
            remainingMicroAlgos = maxOf(0, remaining),
            blocksWatched = blocksConsumed,
        )
    }

    /**
     * Check if funds are depleted
     */
    fun isFundsDepleted(blocksConsumed: Int): Boolean {
        val consumed = blocksConsumed * COST_PER_BLOCK_MICRO_ALGOS
        return consumed >= 1_000_000L
    }
}

sealed class PaymentVerificationResult {
    data class Valid(
        val senderAddress: String,
        val signedTransactionBytes: ByteArray,
        val transactionId: String,
    ) : PaymentVerificationResult()

    data class Invalid(
        val reason: String,
    ) : PaymentVerificationResult()
}
