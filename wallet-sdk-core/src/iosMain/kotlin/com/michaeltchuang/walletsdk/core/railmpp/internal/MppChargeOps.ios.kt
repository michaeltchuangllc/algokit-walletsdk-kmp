package com.michaeltchuang.walletsdk.core.railmpp.internal

import AlgorandIosSdk.spmAlgoApiBridge
import io.github.aakira.napier.Napier
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.Foundation.NSData
import platform.posix.memcpy
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val TAG = "MppChargeOps.ios"
private const val ROUND_VALIDITY_WINDOW = 1000L
private const val DEFAULT_MIN_FEE = 1000L

@OptIn(ExperimentalForeignApi::class)
private val bridge by lazy { spmAlgoApiBridge() }

@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun mppFetchSuggestedParams(algodUrl: String): MppBuildParams {
    val json = bridge.syncGetTxParamsWithAlgodUrl(algodUrl = algodUrl)
    if (json.isEmpty()) error("iOS: fetchTxParams returned empty for $algodUrl")
    val lastRound = parseJsonLong(json, "last-round") ?: error("iOS: missing last-round in params")
    val genesisHashB64 = parseJsonString(json, "genesis-hash") ?: error("iOS: missing genesis-hash")
    val genesisId = parseJsonString(json, "genesis-id") ?: "testnet-v1.0"
    val fee = parseJsonLong(json, "fee") ?: 0L
    val minFee = parseJsonLong(json, "min-fee") ?: DEFAULT_MIN_FEE
    return MppBuildParams(
        lastRound = lastRound,
        genesisHashB64 = normalizeBase64(genesisHashB64),
        genesisId = genesisId,
        fee = fee,
        minFee = minFee,
    )
}

@OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)
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
            normalizedAsaId == "0" ||
            normalizedAsaId.equals("algo", ignoreCase = true)
    val noteB64 = note?.let { Base64.encode(it) }
    val firstRound = params.lastRound
    val lastRoundValid = params.lastRound + ROUND_VALIDITY_WINDOW

    // Fee payer covers fees → consumer txn pays 0 (flat). Otherwise use algod's fee-per-byte.
    val flatFee = useFeePayer
    val fee = if (useFeePayer) 0L else params.fee

    val txnData: NSData =
        if (isAlgo) {
            bridge.makePaymentTxnWithSenderAddress(
                senderAddress = sender,
                receiverAddress = receiver,
                amount = amount.toString(),
                isMax = false,
                noteBase64 = noteB64,
                fee = fee,
                flatFee = flatFee,
                firstRound = firstRound,
                lastRound = lastRoundValid,
                genesisHashBase64 = params.genesisHashB64,
                genesisID = params.genesisId,
            )
        } else {
            val asaIdLong = parseMppAsaId(normalizedAsaId, context = "ASA transfer")
            bridge.makeAssetTransferTxnWithSenderAddress(
                senderAddress = sender,
                receiverAddress = receiver,
                amount = amount.toString(),
                assetId = asaIdLong,
                noteBase64 = noteB64,
                fee = fee,
                flatFee = flatFee,
                firstRound = firstRound,
                lastRound = lastRoundValid,
                genesisHashBase64 = params.genesisHashB64,
                genesisID = params.genesisId,
            )
        }
    val txnBytes = txnData.toKotlinByteArray()
    if (txnBytes.isEmpty()) error("iOS: build payment txn returned empty")

    // Lease is required by the charge spec — inject it via the AlgoKitTransact bridge.
    if (lease != null) {
        val leasedB64 =
            bridge.setTxnLeaseWithTxnBase64(
                txnBase64 = Base64.encode(txnBytes),
                leaseBase64 = Base64.encode(lease),
            )
        if (leasedB64.isEmpty()) error("iOS: setTxnLease returned empty")
        return Base64.decode(leasedB64)
    }
    return txnBytes
}

@OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)
internal actual fun mppBuildFeePayerTxn(
    feePayerAddress: String,
    params: MppBuildParams,
    pooledFee: Long,
    note: ByteArray?,
): ByteArray {
    val noteB64 = note?.let { Base64.encode(it) }
    val txnData =
        bridge.makePaymentTxnWithSenderAddress(
            senderAddress = feePayerAddress,
            receiverAddress = feePayerAddress,
            amount = "0",
            isMax = false,
            noteBase64 = noteB64,
            fee = pooledFee,
            flatFee = true,
            firstRound = params.lastRound,
            lastRound = params.lastRound + ROUND_VALIDITY_WINDOW,
            genesisHashBase64 = params.genesisHashB64,
            genesisID = params.genesisId,
        )
    val txnBytes = txnData.toKotlinByteArray()
    if (txnBytes.isEmpty()) error("iOS: build fee payer txn returned empty")
    return txnBytes
}

@OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)
internal actual fun mppAssignGroup(unsignedTxns: List<ByteArray>): List<ByteArray> {
    val b64In = unsignedTxns.map { Base64.encode(it) }
    val grouped = bridge.assignGroupIdsWithTxnsBase64(txnsBase64 = b64In)
    if (grouped.size < unsignedTxns.size) error("iOS: assignGroupIds returned ${grouped.size} txns")
    return grouped.map { Base64.decode(normalizeBase64(it.toString())) }
}

@OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)
internal actual fun mppDecodeTxn(
    bytes: ByteArray,
    isFeePayerSlot: Boolean,
): MppDecodedTxn {
    val json =
        bridge.decodeChargeTxnJsonWithTxnBase64(
            txnBase64 = Base64.encode(bytes),
            allowUnsigned = isFeePayerSlot,
        )
    if (json.isEmpty()) {
        throw MppVerifyException("iOS: could not decode transaction (feePayerSlot=$isFeePayerSlot, bytes=${bytes.size})")
    }
    val obj = Json.parseToJsonElement(json).jsonObject
    val isSigned = obj.bool("signed") ?: false
    return MppDecodedTxn(
        type = obj.str("type") ?: "",
        sender = obj.str("sender"),
        receiver = obj.str("receiver"),
        amount = obj.str("amount")?.toLongOrNull(),
        assetReceiver = obj.str("assetReceiver"),
        assetAmount = obj.str("assetAmount")?.toLongOrNull(),
        xferAsset = obj.str("xferAsset")?.toLongOrNull(),
        lease = obj.str("leaseB64")?.let { Base64.decode(normalizeBase64(it)) },
        groupId = obj.str("groupB64")?.let { Base64.decode(normalizeBase64(it)) },
        hasCloseRemainderTo = obj.bool("hasCloseRemainderTo") ?: false,
        hasAssetCloseTo = obj.bool("hasAssetCloseTo") ?: false,
        hasRekeyTo = obj.bool("hasRekeyTo") ?: false,
        computedTxId = obj.str("txId"),
        signedRaw = if (isSigned) bytes else null,
        unsignedRaw = if (!isSigned) bytes else null,
    )
}

@OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)
internal actual suspend fun mppBroadcastGroup(
    algodUrl: String,
    signedBlobs: List<ByteArray>,
): String? {
    val concatenated = signedBlobs.fold(ByteArray(0)) { acc, b -> acc + b }
    val response =
        bridge.syncBroadcastTxnsWithAlgodUrl(
            algodUrl = algodUrl,
            signedTxnsBase64 = Base64.encode(concatenated),
        )
    if (response.isEmpty()) error("iOS: broadcast returned empty response")
    if (response.startsWith("BROADCAST_ERROR:")) {
        val body = response.removePrefix("BROADCAST_ERROR:")
        val msg = parseJsonString(body, "message") ?: body.take(200)
        throw MppVerifyException("iOS: broadcast failed — $msg")
    }
    val txId = parseJsonString(response, "txId") ?: parseJsonString(response, "txid")
    Napier.d("[BROADCAST_ALGO_OK] txId=${txId ?: "null"} txCount=${signedBlobs.size}", tag = TAG)
    return txId
}

// ── Private helpers ──────────────────────────────────────────────────────────

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.bool(key: String): Boolean? = runCatching { this[key]?.jsonPrimitive?.boolean }.getOrNull()

/** Normalises URL-safe base64 to standard base64 with padding. */
private fun normalizeBase64(s: String): String {
    val standard = s.replace('-', '+').replace('_', '/')
    val pad = (4 - standard.length % 4) % 4
    return if (pad > 0) standard + "=".repeat(pad) else standard
}

private fun parseJsonString(
    json: String,
    key: String,
): String? = Regex(""""$key"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.getOrNull(1)

private fun parseJsonLong(
    json: String,
    key: String,
): Long? =
    Regex(""""$key"\s*:\s*(-?\d+)""")
        .find(json)
        ?.groupValues
        ?.getOrNull(1)
        ?.toLongOrNull()

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toKotlinByteArray(): ByteArray {
    val length = this.length.toInt()
    if (length == 0) return ByteArray(0)
    return ByteArray(length).apply {
        usePinned { pinned ->
            memcpy(pinned.addressOf(0), this@toKotlinByteArray.bytes, this@toKotlinByteArray.length)
        }
    }
}
