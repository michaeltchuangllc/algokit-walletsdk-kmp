package com.michaeltchuang.walletsdk.core.railmpp.internal

import AlgorandIosSdk.spmAlgoApiBridge
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSignerType
import io.github.aakira.napier.Napier
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSThread
import platform.posix.memcpy
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val TAG = "AlgorandOps.ios"
private const val APP_CALL_FEE = 12_000L
private const val MIN_TXN_FEE = 1_000L

@OptIn(ExperimentalForeignApi::class)
private val bridge by lazy { spmAlgoApiBridge() }

// ── Expect implementations ──────────────────────────────────────────────────

@OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)
internal actual fun getSessionBoxBytesInternal(
    appId: Long,
    channelId: ByteArray,
    algodUrl: String,
): ByteArray {
    // Algod's `b64:` box-name selector expects STANDARD base64 (with padding), matching the
    // Android path (Encoder.encodeToBase64). URL-safe/unpadded base64 decodes to different bytes
    // → wrong box → empty response. Swift percent-encodes this value for the URL query string.
    val boxNameB64 = Base64.encode(channelId)
    // Swift: syncGetAlgodBox(algodUrl:appId:boxNameBase64:) → Kotlin: syncGetAlgodBoxWithAlgodUrl
    val json =
        bridge.syncGetAlgodBoxWithAlgodUrl(
            algodUrl = algodUrl,
            appId = appId,
            boxNameBase64 = boxNameB64,
        )
    if (json.isEmpty()) error("iOS: Box fetch returned empty for appId=$appId")
    val valueB64 = parseJsonString(json, "value") ?: error("iOS: No 'value' in box response")
    return Base64.decode(normalizeBase64(valueB64))
}

@OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)
internal actual suspend fun submitAppCallInternal(
    signer: MppWalletSigner,
    appId: Long,
    usdcAssetId: Long,
    algodUrl: String,
    args: List<ByteArray>,
    boxKeys: List<Pair<Long, ByteArray>>,
    foreignAssets: List<Long>,
    foreignAccounts: List<String>,
): String {
    val params = fetchTxParams(algodUrl)

    val argsB64 = args.map { Base64.encode(it) }
    val boxRefAppIds = boxKeys.map { it.first }
    val boxRefNamesB64 = boxKeys.map { Base64.encode(it.second) }

    // Swift: buildAppCallTxn(senderAddress:appId:...) → Kotlin: buildAppCallTxnWithSenderAddress
    // close()/withdraw() refund the counterparty via inner asset transfers, so the payer/payee
    // accounts read from the channel box must be referenced here, otherwise the AVM rejects the
    // inner txn with "unavailable Account".
    val txnBytes =
        bridge.buildAppCallTxnWithSenderAddress(
            senderAddress = signer.address,
            appId = appId,
            appArgsBase64 = argsB64,
            boxRefAppIds = boxRefAppIds,
            boxRefNamesBase64 = boxRefNamesB64,
            foreignAssets = foreignAssets,
            foreignAccountAddresses = foreignAccounts,
            fee = APP_CALL_FEE,
            firstRound = params.firstRoundValid,
            lastRound = params.lastRoundValid,
            genesisHashBase64 = params.genesisHashBase64,
            genesisID = params.genesisID,
        )
    if (txnBytes.length == 0UL) error("iOS: buildAppCallTxn returned empty")

    val signedBytes = signer.signTransactionBytes(txnBytes.toKotlinByteArray())
    val signedB64 = Base64.encode(signedBytes)
    return broadcastAndGetTxId(algodUrl, signedB64)
}

@OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)
internal actual suspend fun submitAssetTransferAndAppCallInternal(
    signer: MppWalletSigner,
    appId: Long,
    usdcAssetId: Long,
    algodUrl: String,
    appCallArgs: List<ByteArray>,
    boxKeys: List<Pair<Long, ByteArray>>,
    appCallForeignAssets: List<Long>,
    depositAmountMicroUsdc: Long,
): String {
    require(depositAmountMicroUsdc > 0L) { "depositAmountMicroUsdc must be > 0" }
    val params = fetchTxParams(algodUrl)

    // Swift: buildAssetTransferToAppTxn(senderAddress:...) → Kotlin: buildAssetTransferToAppTxnWithSenderAddress
    val axferBytes =
        bridge.buildAssetTransferToAppTxnWithSenderAddress(
            senderAddress = signer.address,
            appId = appId,
            assetId = usdcAssetId,
            amount = depositAmountMicroUsdc,
            fee = MIN_TXN_FEE,
            firstRound = params.firstRoundValid,
            lastRound = params.lastRoundValid,
            genesisHashBase64 = params.genesisHashBase64,
            genesisID = params.genesisID,
        )
    if (axferBytes.length == 0UL) error("iOS: buildAssetTransferToAppTxn returned empty")

    val argsB64 = appCallArgs.map { Base64.encode(it) }
    val boxRefAppIds = boxKeys.map { it.first }
    val boxRefNamesB64 = boxKeys.map { Base64.encode(it.second) }
    Napier.d("[iOS_BOX_KEYS] count=${boxKeys.size} names=${boxRefNamesB64.joinToString { it.take(12) }}", tag = TAG)

    // Swift: buildAppCallTxn(senderAddress:...) → Kotlin: buildAppCallTxnWithSenderAddress
    val appCallBytes =
        bridge.buildAppCallTxnWithSenderAddress(
            senderAddress = signer.address,
            appId = appId,
            appArgsBase64 = argsB64,
            boxRefAppIds = boxRefAppIds,
            boxRefNamesBase64 = boxRefNamesB64,
            foreignAssets = appCallForeignAssets,
            foreignAccountAddresses = emptyList<String>(),
            fee = APP_CALL_FEE,
            firstRound = params.firstRoundValid,
            lastRound = params.lastRoundValid,
            genesisHashBase64 = params.genesisHashBase64,
            genesisID = params.genesisID,
        )
    if (appCallBytes.length == 0UL) error("iOS: buildAppCallTxn returned empty")

    val axferRawBytes = axferBytes.toKotlinByteArray()
    val appCallRawBytes = appCallBytes.toKotlinByteArray()

    val allSignedBytes: ByteArray

    if (signer.signerType == MppWalletSignerType.FALCON_LSIG) {
        // ── Falcon signer path (mirrors the working Android flow) ───────────────────
        // Hand the two real, UNGROUPED transactions to the Falcon bundle signer. With no
        // group ID present, the Go SDK (AlgoSdkSignFalconBundle) adds its own budget "dummy"
        // transactions — each with a MINIMAL LogicSig — then assigns the group ID and signs
        // everything. This keeps the AVM LogicSig pool (1000 bytes × txn count) large enough
        // for the two ~3 KB Falcon LogicSigs without us managing dummies ourselves.
        //
        // We deliberately do NOT build our own dummies / always-true escrow anymore: a
        // globally-shared always-true account is hijackable/rekeyable on public networks,
        // which caused "should have been authorized by X but was authorized by Y" rejections.
        //
        // The appCall's APP_CALL_FEE (12_000 µAlgo) provides the fee-pool budget that covers
        // the SDK-added dummies' fees. Box refs survive because the unsigned appCall already
        // carries correct (patched) box names and the SDK round-trips the apbx field intact.
        val realTxns = listOf(axferRawBytes, appCallRawBytes)
        Napier.d("[iOS_FALCON] handing ${realTxns.size} real txns to SDK bundle signer (SDK adds dummies)", tag = TAG)

        val signedTxns = signer.signTransactionsBytes(realTxns)
        if (signedTxns.isEmpty() || signedTxns.all { it.isEmpty() }) error("iOS: Falcon group bundle signing failed")
        Napier.d("[iOS_FALCON] signed ${signedTxns.size} txns (real + SDK dummies)", tag = TAG)
        allSignedBytes = signedTxns.fold(ByteArray(0)) { acc, b -> acc + b }
    } else {
        // Non-Falcon: assign group IDs first, then sign each transaction individually.
        // Swift: assignGroupIds(txnsBase64:) → Kotlin: assignGroupIdsWithTxnsBase64
        val axferB64 = Base64.encode(axferRawBytes)
        val appCallB64 = Base64.encode(appCallRawBytes)
        val groupedB64List = bridge.assignGroupIdsWithTxnsBase64(txnsBase64 = listOf(axferB64, appCallB64))
        if (groupedB64List.size < 2) error("iOS: assignGroupIds returned ${groupedB64List.size} txns")

        val signedTxns = mutableListOf<ByteArray>()
        for (txnB64Item in groupedB64List) {
            val txnBytes = Base64.decode(normalizeBase64(txnB64Item.toString()))
            signedTxns.add(signer.signTransactionBytes(txnBytes))
        }
        allSignedBytes = signedTxns.fold(ByteArray(0)) { acc, b -> acc + b }
    }

    return broadcastAndGetTxId(algodUrl, Base64.encode(allSignedBytes))
}

internal actual fun decodeMsgPackAny(bytes: ByteArray): Any? = null

@OptIn(ExperimentalForeignApi::class)
internal actual fun awaitConfirmationDetailsInternal(
    txId: String,
    algodUrl: String,
    maxRounds: Int,
): Pair<Long, Int> {
    var last: Pair<Long, Int> = Pair(0L, 0)
    repeat(maxRounds) {
        // Swift: syncGetPendingTxn(algodUrl:txId:) → Kotlin: syncGetPendingTxnWithAlgodUrl
        val json = bridge.syncGetPendingTxnWithAlgodUrl(algodUrl = algodUrl, txId = txId)
        if (json.isNotEmpty()) {
            val confirmedRound = parseJsonLong(json, "confirmed-round") ?: 0L
            val logCount = parseJsonInt(json, "logs") ?: 0
            last = Pair(confirmedRound, logCount)
            if (confirmedRound > 0L) return last
        }
        NSThread.sleepForTimeInterval(0.7)
    }
    return last
}

internal actual fun awaitConfirmationInternal(
    txId: String,
    algodUrl: String,
    maxRounds: Int,
): Boolean {
    val (round, _) = awaitConfirmationDetailsInternal(txId, algodUrl, maxRounds)
    return round > 0L
}

// ── Private helpers ─────────────────────────────────────────────────────────

private data class AlgodTxParams(
    val firstRoundValid: Long,
    val lastRoundValid: Long,
    val genesisHashBase64: String,
    val genesisID: String,
    val minFee: Long,
)

@OptIn(ExperimentalForeignApi::class)
private fun fetchTxParams(algodUrl: String): AlgodTxParams {
    // Swift: syncGetTxParams(algodUrl:) → Kotlin: syncGetTxParamsWithAlgodUrl
    val json = bridge.syncGetTxParamsWithAlgodUrl(algodUrl = algodUrl)
    if (json.isEmpty()) error("iOS: fetchTxParams returned empty for $algodUrl")
    val lastRound = parseJsonLong(json, "last-round") ?: error("iOS: missing last-round in params")
    val genesisHashB64 = parseJsonString(json, "genesis-hash") ?: error("iOS: missing genesis-hash")
    val genesisID = parseJsonString(json, "genesis-id") ?: "testnet-v1.0"
    val minFee = parseJsonLong(json, "min-fee") ?: 1000L
    return AlgodTxParams(
        firstRoundValid = lastRound,
        lastRoundValid = lastRound + 1000L,
        genesisHashBase64 = normalizeBase64(genesisHashB64),
        genesisID = genesisID,
        minFee = minFee,
    )
}

@OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)
private fun broadcastAndGetTxId(
    algodUrl: String,
    signedBase64: String,
): String {
    // Swift: syncBroadcastTxns(algodUrl:signedTxnsBase64:) → Kotlin: syncBroadcastTxnsWithAlgodUrl
    val responseJson =
        bridge.syncBroadcastTxnsWithAlgodUrl(
            algodUrl = algodUrl,
            signedTxnsBase64 = signedBase64,
        )
    if (responseJson.isEmpty()) error("iOS: broadcast returned empty response")
    // Swift propagates non-2xx errors as "BROADCAST_ERROR:<algod json body>"
    if (responseJson.startsWith("BROADCAST_ERROR:")) {
        val body = responseJson.removePrefix("BROADCAST_ERROR:")
        val msg = parseJsonString(body, "message") ?: body.take(200)
        error("iOS: broadcast failed — $msg")
    }
    val txId =
        parseJsonString(responseJson, "txId")
            ?: parseJsonString(responseJson, "txid")
            ?: error("iOS: no txId in broadcast response: $responseJson")
    Napier.d("[iOS_BROADCAST_OK] txId=$txId", tag = TAG)
    return txId
}

/** Normalises URL-safe base64 to standard base64 with padding. */
private fun normalizeBase64(s: String): String {
    val standard = s.replace('-', '+').replace('_', '/')
    val pad = (4 - standard.length % 4) % 4
    return if (pad > 0) standard + "=".repeat(pad) else standard
}

/** Extracts a JSON string value for [key] using simple regex. */
private fun parseJsonString(
    json: String,
    key: String,
): String? =
    Regex(""""$key"\s*:\s*"([^"]*)"()""")
        .find(json)
        ?.groupValues
        ?.getOrNull(1)
        ?.takeIf { it.isNotEmpty() }
        ?: Regex(""""$key"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.getOrNull(1)

/** Extracts a JSON long value for [key]. */
private fun parseJsonLong(
    json: String,
    key: String,
): Long? =
    Regex(""""$key"\s*:\s*(-?\d+)""")
        .find(json)
        ?.groupValues
        ?.getOrNull(1)
        ?.toLongOrNull()

/** Extracts a JSON int value for [key] (arrays are counted). */
private fun parseJsonInt(
    json: String,
    key: String,
): Int? = parseJsonLong(json, key)?.toInt()

/** Extension: converts NSData to KotlinByteArray. */
@OptIn(ExperimentalForeignApi::class)
private fun platform.Foundation.NSData.toKotlinByteArray(): ByteArray {
    val length = this.length.toInt()
    if (length == 0) return ByteArray(0)
    return ByteArray(length).apply {
        usePinned { pinned ->
            memcpy(pinned.addressOf(0), this@toKotlinByteArray.bytes, this@toKotlinByteArray.length)
        }
    }
}
