package com.michaeltchuang.walletsdk.core.railmpp.internal

import android.util.Base64
import android.util.Log
import com.algorand.algosdk.crypto.Address
import com.algorand.algosdk.transaction.SignedTransaction
import com.algorand.algosdk.transaction.Transaction
import com.algorand.algosdk.transaction.TxGroup
import com.algorand.algosdk.util.Encoder
import com.algorand.algosdk.v2.client.common.AlgodClient
import com.algorand.algosdk.v2.client.common.Response
import com.algorand.algosdk.v2.client.model.PostTransactionsResponse
import com.algorand.algosdk.v2.client.model.TransactionParametersResponse
import com.michaeltchuang.walletsdk.core.railmpp.ALGO_ASSET
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigInteger
import java.net.URI

private const val TAG = "MppChargeOps.android"

internal actual suspend fun mppFetchSuggestedParams(algodUrl: String): MppBuildParams =
    withContext(Dispatchers.IO) {
        val resp = algodClient(algodUrl).TransactionParams().execute().body()
        MppBuildParams(
            lastRound = resp.lastRound,
            genesisHashB64 = Base64.encodeToString(resp.genesisHash, Base64.NO_WRAP),
            genesisId = resp.genesisId,
            fee = resp.fee,
            minFee = resp.minFee,
        )
    }

internal actual fun mppBuildPaymentTxn(
    sender: String,
    receiver: String,
    amount: Long,
    asaId: String?,
    params: MppBuildParams,
    lease: ByteArray?,
    note: ByteArray?,
    useFeePayer: Boolean,
): ByteArray {
    val sp = params.toResponse()
    val normalizedAsaId = asaId?.trim()
    val isAlgo =
        normalizedAsaId == null ||
            normalizedAsaId == ALGO_ASSET ||
            normalizedAsaId.equals("algo", ignoreCase = true)

    val txn: Transaction =
        if (isAlgo) {
            Transaction
                .PaymentTransactionBuilder()
                .sender(Address(sender))
                .receiver(Address(receiver))
                .amount(amount)
                .suggestedParams(sp)
                .apply { if (note != null) note(note) }
                .build()
        } else {
            val asaIdLong = parseMppAsaId(normalizedAsaId, context = "ASA transfer")
            Transaction
                .AssetTransferTransactionBuilder()
                .sender(Address(sender))
                .assetReceiver(Address(receiver))
                .assetAmount(amount)
                .assetIndex(asaIdLong)
                .suggestedParams(sp)
                .apply { if (note != null) note(note) }
                .build()
        }

    if (useFeePayer) {
        txn.fee = BigInteger.ZERO
    }
    if (lease != null) {
        txn.lease = lease
    }
    return Encoder.encodeToMsgPack(txn)
}

internal actual fun mppBuildFeePayerTxn(
    feePayerAddress: String,
    params: MppBuildParams,
    pooledFee: Long,
    note: ByteArray?,
): ByteArray {
    val sp = params.toResponse()
    val txn =
        Transaction
            .PaymentTransactionBuilder()
            .sender(Address(feePayerAddress))
            .receiver(Address(feePayerAddress))
            .amount(0)
            .suggestedParams(sp)
            .apply { if (note != null) note(note) }
            .build()
    txn.fee = BigInteger.valueOf(pooledFee)
    return Encoder.encodeToMsgPack(txn)
}

internal actual fun mppAssignGroup(unsignedTxns: List<ByteArray>): List<ByteArray> {
    val txns =
        unsignedTxns
            .map { Encoder.decodeFromMsgPack(it, Transaction::class.java) }
            .toTypedArray()
    TxGroup.assignGroupID(*txns)
    return txns.map { Encoder.encodeToMsgPack(it) }
}

internal actual fun mppDecodeTxn(
    bytes: ByteArray,
    isFeePayerSlot: Boolean,
): MppDecodedTxn {
    // Try signed first (most common); fall back to unsigned for the fee payer slot.
    val signed =
        try {
            Encoder.decodeFromMsgPack(bytes, SignedTransaction::class.java)
        } catch (signedDecodeErr: Exception) {
            if (!isFeePayerSlot) {
                throw MppVerifyException(
                    "Could not decode signed transaction at non-fee-payer slot: bytes=${bytes.size}. " +
                        "signedDecode=${signedDecodeErr.message}.",
                )
            }
            return try {
                val unsigned = Encoder.decodeFromMsgPack(bytes, Transaction::class.java)
                unsigned.flatten(signedRaw = null, unsignedRaw = bytes)
            } catch (e: Exception) {
                throw MppVerifyException("Could not decode unsigned fee payer txn: ${e.message}")
            }
        }

    val txn = signed.tx ?: throw MppVerifyException("Signed txn missing inner txn body")
    return txn.flatten(signedRaw = bytes, unsignedRaw = null)
}

internal actual suspend fun mppBroadcastGroup(
    algodUrl: String,
    signedBlobs: List<ByteArray>,
): String? =
    withContext(Dispatchers.IO) {
        val concatenated = signedBlobs.fold(ByteArray(0)) { acc, b -> acc + b }
        val resp: Response<PostTransactionsResponse> =
            algodClient(algodUrl).RawTransaction().rawtxn(concatenated).execute()
        if (!resp.isSuccessful) {
            val err = resp.message() ?: "algod rejected the group"
            Log.e(TAG, "[BROADCAST_ALGO_FAILED] error=$err txCount=${signedBlobs.size}")
            throw MppVerifyException("Broadcast failed: $err")
        }
        val txId = resp.body()?.txId
        Log.d(TAG, "[BROADCAST_ALGO_OK] txId=${txId ?: "null"} txCount=${signedBlobs.size}")
        txId
    }

// ── Private helpers ──────────────────────────────────────────────────────────

private fun MppBuildParams.toResponse(): TransactionParametersResponse =
    TransactionParametersResponse().also {
        it.lastRound = lastRound
        it.fee = fee
        it.minFee = minFee
        it.genesisId = genesisId
        it.genesisHash = Base64.decode(genesisHashB64, Base64.NO_WRAP)
    }

private fun Transaction.flatten(
    signedRaw: ByteArray?,
    unsignedRaw: ByteArray?,
): MppDecodedTxn {
    val typeStr =
        when (type) {
            Transaction.Type.Payment -> MppDecodedTxn.TYPE_PAYMENT
            Transaction.Type.AssetTransfer -> MppDecodedTxn.TYPE_ASSET_TRANSFER
            else -> type?.toString() ?: ""
        }
    val computedTxId =
        try {
            txID()
        } catch (_: Exception) {
            null
        }
    return MppDecodedTxn(
        type = typeStr,
        sender = sender?.toString(),
        receiver = receiver?.toString(),
        amount = amount?.toLong(),
        assetReceiver = assetReceiver?.toString(),
        assetAmount = assetAmount?.toLong(),
        xferAsset = xferAsset?.toLong(),
        lease = lease?.takeIf { it.isNotEmpty() },
        groupId = group?.bytes,
        hasCloseRemainderTo = closeRemainderTo.hasNonZeroBytes(),
        hasAssetCloseTo = assetCloseTo.hasNonZeroBytes(),
        hasRekeyTo = rekeyTo.hasNonZeroBytes(),
        computedTxId = computedTxId,
        signedRaw = signedRaw,
        unsignedRaw = unsignedRaw,
    )
}

private fun Address?.hasNonZeroBytes(): Boolean = this != null && bytes.any { it != 0.toByte() }

private fun algodClient(url: String): AlgodClient {
    val parsed = URI(url)
    val port =
        when {
            parsed.port > 0 -> parsed.port
            parsed.scheme == "https" -> 443
            else -> 80
        }
    val host = "${parsed.scheme}://${parsed.host}"
    return AlgodClient(host, port, "")
}
