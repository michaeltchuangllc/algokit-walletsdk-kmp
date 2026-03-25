package com.michaeltchuang.walletsdk.ui.liquidAuth.payments

import android.util.Log
import com.algorand.algosdk.transaction.SignedTransaction
import com.algorand.algosdk.util.Encoder
import com.michaeltchuang.walletsdk.core.algosdk.makePaymentTxn
import com.michaeltchuang.walletsdk.core.algosdk.signAlgo25Transaction
import com.michaeltchuang.walletsdk.core.foundation.utils.SuggestedParams
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.model.SessionVault
import com.michaeltchuang.walletsdk.ui.liquidAuth.model.MppPaymentMessages

/**
 * MPP Payment Helper for Liquid Auth
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
object AlgorandMppPayments {
    private const val TAG = "MPPPayments"
    private const val DEPOSIT_MICRO_ALGOS = "1000000" // 1 ALGO

    /**
     * Create MPP deposit payment transaction (unsigned)
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
        val note = "MPP:$sessionId".toByteArray()
        return makePaymentTxn(
            senderAddress = senderAddress,
            receiverAddress = creatorAddress,
            amount = DEPOSIT_MICRO_ALGOS,
            isMax = false,
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
     * Create balance update message for client from current vault state.
     */
    fun createBalanceUpdate(vault: SessionVault): MppPaymentMessages.BalanceUpdate =
        MppPaymentMessages.BalanceUpdate(
            id = vault.sessionId,
            initialDepositMicroAlgos = vault.initialDepositMicroUnits,
            consumedMicroAlgos = vault.consumedMicroUnits,
            remainingMicroAlgos = vault.remainingMicroUnits,
            blocksWatched = vault.blocksConsumed,
            costPerBlockMicroAlgos = vault.costPerBlockMicroUnits,
        )
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
