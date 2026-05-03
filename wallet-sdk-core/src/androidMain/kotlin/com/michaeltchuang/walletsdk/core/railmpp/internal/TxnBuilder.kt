package com.michaeltchuang.walletsdk.core.railmpp.internal

import com.algorand.algosdk.crypto.Address
import com.algorand.algosdk.transaction.Transaction
import com.algorand.algosdk.transaction.TxGroup
import com.algorand.algosdk.util.Encoder
import com.algorand.algosdk.v2.client.common.AlgodClient
import com.algorand.algosdk.v2.client.model.TransactionParametersResponse
import com.michaeltchuang.walletsdk.core.railmpp.ALGO_ASSET
import xyz.goplausible.webrtcpaymentsdk.railmpp.spec.Base64Std
import java.math.BigInteger

/**
 * Pure-Kotlin txn group construction matching the algorand charge spec.
 *
 * Used by:
 *  - Consumer (`createRailPayment`) — builds and signs the group
 *  - Provider (`verifyAndSettle`) — assigns group id, signs fee payer, broadcasts
 *
 * No coroutines here; callers wrap in `withContext(Dispatchers.IO)` for the
 * algod fetch + broadcast.
 */
internal object TxnBuilder {
    /** Build the payment transaction (ALGO `pay` or ASA `axfer`). */
    fun buildPaymentTxn(
        sender: String,
        receiver: String,
        amount: Long,
        asaId: String?,
        params: TransactionParametersResponse,
        lease: ByteArray?,
        note: ByteArray?,
        useFeePayer: Boolean,
    ): Transaction {
        val normalizedAsaId = asaId?.trim()
        val isAlgo = normalizedAsaId == null || normalizedAsaId == ALGO_ASSET || normalizedAsaId.equals("algo", ignoreCase = true)

        val txn: Transaction =
            if (isAlgo) {
                Transaction
                    .PaymentTransactionBuilder()
                    .sender(Address(sender))
                    .receiver(Address(receiver))
                    .amount(amount)
                    .suggestedParams(params)
                    .apply { if (note != null) note(note) }
                    .build()
            } else {
                // asaId is non-null here by the isAlgo check above, but Kotlin
                // smart-cast doesn't carry through the conjunction — assert explicitly.
                val asaIdLong = requireNotNull(normalizedAsaId) { "asaId required for ASA transfer" }.toLong()
                Transaction
                    .AssetTransferTransactionBuilder()
                    .sender(Address(sender))
                    .assetReceiver(Address(receiver))
                    .assetAmount(amount)
                    .assetIndex(asaIdLong)
                    .suggestedParams(params)
                    .apply { if (note != null) note(note) }
                    .build()
            }

        if (useFeePayer) {
            // Fee payer covers all fees — consumer txn pays 0.
            txn.fee = BigInteger.ZERO
        }
        if (lease != null) {
            txn.lease = lease
        }
        return txn
    }

    /** Build the unsigned fee payer txn — 0-ALGO self-payment with pooled fee. */
    fun buildFeePayerTxn(
        feePayerAddress: String,
        params: TransactionParametersResponse,
        pooledFee: Long,
        note: ByteArray?,
    ): Transaction {
        val txn =
            Transaction
                .PaymentTransactionBuilder()
                .sender(Address(feePayerAddress))
                .receiver(Address(feePayerAddress))
                .amount(0)
                .suggestedParams(params)
                .apply { if (note != null) note(note) }
                .build()
        txn.fee = BigInteger.valueOf(pooledFee)
        return txn
    }

    /** Assign group id (mutates each txn's `grp` field). Returns the same txns as a List. */
    fun assignGroup(vararg txns: Transaction): List<Transaction> {
        TxGroup.assignGroupID(*txns)
        return txns.toList()
    }

    /** Encode a single (signed or unsigned) txn as base64-msgpack. */
    fun encodeTxnBase64(txn: Transaction): String {
        val bytes = Encoder.encodeToMsgPack(txn)
        return Base64Std.encode(bytes)
    }

    fun encodeSignedTxnBase64(signedBytes: ByteArray): String = Base64Std.encode(signedBytes)

    /** Fetch the network's suggested params from algod. */
    fun fetchSuggestedParams(algodClient: AlgodClient): TransactionParametersResponse = algodClient.TransactionParams().execute().body()
}
