package com.michaeltchuang.walletsdk.core.railmpp.utils

import android.util.Log
import com.algorand.algosdk.transaction.SignedTransaction
import com.algorand.algosdk.util.Encoder
import com.michaeltchuang.walletsdk.core.algosdk.makeAssetTransferTxn
import com.michaeltchuang.walletsdk.core.foundation.utils.SuggestedParams

/**
 * MPP payment helper for Liquid Stream.
 *
 * Note: current implementation supports Algorand and Solana transfer construction/signing.
 */
object MppPayments {
    private const val TAG = "MppPayments"
    private const val DEPOSIT_MICRO_ALGOS = "1000000" // 1 ALGO
    private const val COST_PER_BLOCK_MICRO_ALGOS = 100_000L // 0.1 ALGO

    fun createDepositTransaction(
        senderAddress: String,
        creatorAddress: String,
        sessionId: String,
        suggestedParams: SuggestedParams,
    ): ByteArray {
        val note = "MPP:$sessionId".toByteArray()
        return makeAssetTransferTxn(
            senderAddress = senderAddress,
            receiverAddress = creatorAddress,
            amount = DEPOSIT_MICRO_ALGOS,
            assetId = 10458941,
            noteInByteArray = note,
            suggestedParams = suggestedParams,
        )
    }
    fun decodeSignedTransaction(bytes: ByteArray): SignedTransaction? =
        try {
            Encoder.decodeFromMsgPack(bytes, SignedTransaction::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode signed transaction", e)
            null
        }

    fun verifyPaymentProof(
        signedTxnBytes: ByteArray,
        expectedCreator: String,
        expectedSessionId: String,
    ): MppPaymentVerificationResult {
        val signedTxn =
            decodeSignedTransaction(signedTxnBytes)
                ?: return MppPaymentVerificationResult.Invalid("Failed to decode transaction")

        val txn = signedTxn.tx

        if (txn.amount.toString() != DEPOSIT_MICRO_ALGOS) {
            return MppPaymentVerificationResult.Invalid(
                "Incorrect amount: expected 1 ALGO, got ${txn.amount.toDouble() / 1_000_000.0} ALGO",
            )
        }

        if (txn.receiver.toString() != expectedCreator) {
            return MppPaymentVerificationResult.Invalid(
                "Incorrect receiver: expected $expectedCreator, got ${txn.receiver}",
            )
        }

        val note = txn.note?.let { String(it) } ?: ""
        if (!note.contains(expectedSessionId)) {
            return MppPaymentVerificationResult.Invalid("Session ID not found in note: $note")
        }

        return MppPaymentVerificationResult.Valid(
            senderAddress = txn.sender.toString(),
            signedTransactionBytes = signedTxnBytes,
            transactionId = txn.txID(),
        )
    }

    fun createBalanceUpdateJson(
        sessionId: String,
        blocksConsumed: Int,
    ): String {
        val consumed = blocksConsumed * COST_PER_BLOCK_MICRO_ALGOS
        val remaining = maxOf(0, 1_000_000L - consumed)

        return """{"reference":"liquid:payment:balance","id":"$sessionId","initialDepositMicroAlgos":1000000,"consumedMicroAlgos":$consumed,"remainingMicroAlgos":$remaining,"blocksWatched":$blocksConsumed,"costPerBlockMicroAlgos":100000}"""
    }

    fun remainingUsdc(blocksConsumed: Int): Double {
        val consumed = blocksConsumed * COST_PER_BLOCK_MICRO_ALGOS
        val remaining = maxOf(0, 1_000_000L - consumed)
        return remaining / 1_000_000.0
    }

    fun isFundsDepleted(blocksConsumed: Int): Boolean {
        val consumed = blocksConsumed * COST_PER_BLOCK_MICRO_ALGOS
        return consumed >= 1_000_000L
    }
}

sealed class MppPaymentVerificationResult {
    data class Valid(
        val senderAddress: String,
        val signedTransactionBytes: ByteArray,
        val transactionId: String,
    ) : MppPaymentVerificationResult()

    data class Invalid(
        val reason: String,
    ) : MppPaymentVerificationResult()
}
