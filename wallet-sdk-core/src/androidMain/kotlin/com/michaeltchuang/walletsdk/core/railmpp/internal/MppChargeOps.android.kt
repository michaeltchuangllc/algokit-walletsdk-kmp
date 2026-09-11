package com.michaeltchuang.walletsdk.core.railmpp.internal

import android.util.Base64
import android.util.Log
import com.michaeltchuang.walletsdk.core.railmpp.ALGO_ASSET
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import uniffi.algokit_transact_ffi.AssetTransferTransactionFields
import uniffi.algokit_transact_ffi.FeeParams
import uniffi.algokit_transact_ffi.PaymentTransactionFields
import uniffi.algokit_transact_ffi.Transaction
import uniffi.algokit_transact_ffi.TransactionType
import uniffi.algokit_transact_ffi.assignFee
import uniffi.algokit_transact_ffi.decodeSignedTransaction
import uniffi.algokit_transact_ffi.decodeTransaction
import uniffi.algokit_transact_ffi.encodeTransactionRaw
import uniffi.algokit_transact_ffi.getTransactionId
import uniffi.algokit_transact_ffi.groupTransactions
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "MppChargeOps.android"
private const val ROUND_VALIDITY_WINDOW = 1000L
private const val HTTP_TIMEOUT_MS = 15_000

// AlgoKitTransact (algokit-core Rust library) instead of the Java SDK's Transaction/TxGroup/
// AlgodClient — matches the iOS bridge, which uses the same Rust core for the charge rail.

internal actual suspend fun mppFetchSuggestedParams(algodUrl: String): MppBuildParams =
    withContext(Dispatchers.IO) {
        val json = httpGetJson(algodUrl, "/v2/transactions/params") ?: error("Failed to fetch suggested params from $algodUrl")
        val lastRound = json.optLong("last-round", -1L).takeIf { it >= 0 } ?: error("Missing last-round in suggested params")
        val genesisHashB64 =
            json.optString("genesis-hash", "").takeIf { it.isNotEmpty() } ?: error("Missing genesis-hash in suggested params")
        MppBuildParams(
            lastRound = lastRound,
            genesisHashB64 = genesisHashB64,
            genesisId = json.optString("genesis-id", "testnet-v1.0"),
            fee = json.optLong("fee", 0L),
            minFee = json.optLong("min-fee", 1000L),
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
    val normalizedAsaId = asaId?.trim()
    val isAlgo =
        normalizedAsaId == null ||
            normalizedAsaId == ALGO_ASSET ||
            normalizedAsaId.equals("algo", ignoreCase = true)
    val genesisHash = Base64.decode(params.genesisHashB64, Base64.DEFAULT)

    val unfeededTxn =
        Transaction(
            transactionType = if (isAlgo) TransactionType.PAYMENT else TransactionType.ASSET_TRANSFER,
            sender = sender,
            firstValid = params.lastRound.toULong(),
            lastValid = (params.lastRound + ROUND_VALIDITY_WINDOW).toULong(),
            genesisHash = genesisHash,
            genesisId = params.genesisId,
            note = note?.takeIf { it.isNotEmpty() },
            lease = lease,
            payment = if (isAlgo) PaymentTransactionFields(receiver = receiver, amount = amount.toULong()) else null,
            assetTransfer =
                if (isAlgo) {
                    null
                } else {
                    val asaIdLong = parseMppAsaId(normalizedAsaId, context = "ASA transfer")
                    AssetTransferTransactionFields(assetId = asaIdLong.toULong(), amount = amount.toULong(), receiver = receiver)
                },
        )

    // Fee payer covers fees → consumer txn pays a flat 0. Otherwise mirror algod's
    // suggested-params fee-per-byte computation (same as the legacy builder's default).
    val txn =
        if (useFeePayer) {
            unfeededTxn.copy(fee = 0uL)
        } else {
            assignFee(unfeededTxn, FeeParams(feePerByte = params.fee.toULong(), minFee = params.minFee.toULong()))
        }
    return encodeTransactionRaw(txn)
}

internal actual fun mppBuildFeePayerTxn(
    feePayerAddress: String,
    params: MppBuildParams,
    pooledFee: Long,
    note: ByteArray?,
): ByteArray {
    val genesisHash = Base64.decode(params.genesisHashB64, Base64.DEFAULT)
    val txn =
        Transaction(
            transactionType = TransactionType.PAYMENT,
            sender = feePayerAddress,
            fee = pooledFee.toULong(),
            firstValid = params.lastRound.toULong(),
            lastValid = (params.lastRound + ROUND_VALIDITY_WINDOW).toULong(),
            genesisHash = genesisHash,
            genesisId = params.genesisId,
            note = note?.takeIf { it.isNotEmpty() },
            payment = PaymentTransactionFields(receiver = feePayerAddress, amount = 0uL),
        )
    return encodeTransactionRaw(txn)
}

internal actual fun mppAssignGroup(unsignedTxns: List<ByteArray>): List<ByteArray> {
    val txns = unsignedTxns.map { decodeTransaction(it) }
    return groupTransactions(txns).map { encodeTransactionRaw(it) }
}

internal actual fun mppDecodeTxn(
    bytes: ByteArray,
    isFeePayerSlot: Boolean,
): MppDecodedTxn {
    // Try signed first (most common); fall back to unsigned for the fee payer slot.
    val signed =
        try {
            decodeSignedTransaction(bytes)
        } catch (signedDecodeErr: Exception) {
            if (!isFeePayerSlot) {
                throw MppVerifyException(
                    "Could not decode signed transaction at non-fee-payer slot: bytes=${bytes.size}. " +
                        "signedDecode=${signedDecodeErr.message}.",
                )
            }
            return try {
                val unsigned = decodeTransaction(bytes)
                unsigned.flatten(signedRaw = null, unsignedRaw = bytes)
            } catch (e: Exception) {
                throw MppVerifyException("Could not decode unsigned fee payer txn: ${e.message}")
            }
        }
    return signed.transaction.flatten(signedRaw = bytes, unsignedRaw = null)
}

internal actual suspend fun mppBroadcastGroup(
    algodUrl: String,
    signedBlobs: List<ByteArray>,
): String? =
    withContext(Dispatchers.IO) {
        val concatenated = signedBlobs.fold(ByteArray(0)) { acc, b -> acc + b }
        val response = httpRequest(algodUrl, "/v2/transactions", "POST", "application/x-binary", concatenated)
        if (response.code !in 200..299) {
            Log.e(TAG, "[BROADCAST_ALGO_FAILED] error=${response.body.take(300)} txCount=${signedBlobs.size}")
            throw MppVerifyException("Broadcast failed: ${response.body.ifBlank { "HTTP ${response.code}" }}")
        }
        val txId = runCatching { JSONObject(response.body).optString("txId", "").takeIf { it.isNotEmpty() } }.getOrNull()
        Log.d(TAG, "[BROADCAST_ALGO_OK] txId=${txId ?: "null"} txCount=${signedBlobs.size}")
        txId
    }

// ── Private helpers ──────────────────────────────────────────────────────────

private fun Transaction.flatten(
    signedRaw: ByteArray?,
    unsignedRaw: ByteArray?,
): MppDecodedTxn {
    val typeStr =
        when (transactionType) {
            TransactionType.PAYMENT -> MppDecodedTxn.TYPE_PAYMENT
            TransactionType.ASSET_TRANSFER -> MppDecodedTxn.TYPE_ASSET_TRANSFER
            else -> transactionType.toString()
        }
    val computedTxId =
        try {
            getTransactionId(this)
        } catch (_: Exception) {
            null
        }
    return MppDecodedTxn(
        type = typeStr,
        sender = sender,
        receiver = payment?.receiver ?: assetTransfer?.receiver,
        amount = (payment?.amount ?: assetTransfer?.amount)?.toLong(),
        assetReceiver = assetTransfer?.receiver,
        assetAmount = assetTransfer?.amount?.toLong(),
        xferAsset = assetTransfer?.assetId?.toLong(),
        lease = lease?.takeIf { it.isNotEmpty() },
        groupId = group,
        hasCloseRemainderTo = payment?.closeRemainderTo != null,
        hasAssetCloseTo = assetTransfer?.closeRemainderTo != null,
        hasRekeyTo = rekeyTo != null,
        computedTxId = computedTxId,
        signedRaw = signedRaw,
        unsignedRaw = unsignedRaw,
    )
}

private data class ChargeHttpResponse(
    val code: Int,
    val body: String,
)

private fun httpRequest(
    algodUrl: String,
    path: String,
    method: String = "GET",
    contentType: String? = null,
    body: ByteArray? = null,
): ChargeHttpResponse {
    val connection = URL(algodUrl.removeSuffix("/") + path).openConnection() as HttpURLConnection
    return try {
        connection.requestMethod = method
        connection.connectTimeout = HTTP_TIMEOUT_MS
        connection.readTimeout = HTTP_TIMEOUT_MS
        connection.setRequestProperty("Accept", "application/json")
        if (contentType != null) connection.setRequestProperty("Content-Type", contentType)
        if (body != null) {
            connection.doOutput = true
            connection.outputStream.use { it.write(body) }
        }
        val code = connection.responseCode
        val text = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }
        ChargeHttpResponse(code, text.orEmpty())
    } finally {
        connection.disconnect()
    }
}

private fun httpGetJson(
    algodUrl: String,
    path: String,
): JSONObject? {
    val response = httpRequest(algodUrl, path)
    if (response.code !in 200..299) {
        Log.w(TAG, "[ALGOD_HTTP_ERROR] path=$path code=${response.code} body=${response.body.take(300)}")
        return null
    }
    return response.body.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
}
