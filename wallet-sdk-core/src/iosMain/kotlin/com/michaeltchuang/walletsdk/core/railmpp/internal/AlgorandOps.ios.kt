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
private const val DUMMIES_PER_REAL_TXN = 3
private const val FALCON_SIGNED_TRANSACTION_GROUP_FEE = MIN_TXN_FEE * (DUMMIES_PER_REAL_TXN + 1)

// Hardened LogicSig programs add encoded-byte fee on Futurenet; the teardown-sweep
// branch grew the settlement LogicSig program, bumping this from 18 to 30 microAlgos.
private const val LOGIC_SIG_SETTLEMENT_GROUP_FEE = 3_030L
private const val LOGIC_SIG_MINIMUM_BALANCE = 100_000L

private val SETTLE_FROM_LOGIC_SIG_SELECTOR = byteArrayOf(0x43, 0x9c.toByte(), 0x5f, 0xb1.toByte())
private val SET_SETTLEMENT_LOGIC_SIG_SELECTOR = byteArrayOf(0x42, 0xd9.toByte(), 0x75, 0xa6.toByte())
private val SETTLEMENT_LOGIC_SIG_BOX_PREFIX = "l".encodeToByteArray()

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
    note: ByteArray?,
): String {
    val params = fetchTxParams(algodUrl)

    val argsB64 = args.map { Base64.encode(it) }
    val boxRefAppIds = boxKeys.map { it.first }
    val boxRefNamesB64 = boxKeys.map { Base64.encode(it.second) }
    val noteB64 = note?.takeIf { it.isNotEmpty() }?.let { Base64.encode(it) }

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
            noteBase64 = noteB64,
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
            noteBase64 = null,
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

@OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)
internal actual suspend fun compileSettlementLogicSigAddressInternal(
    appId: Long,
    algodUrl: String,
    channelId: ByteArray,
    authorizedSignerPublicKey: ByteArray,
    payeeAddress: String,
): String {
    require(channelId.size == 32) { "channelId must be 32 bytes" }
    val encodedChannelId = encodeArc4DynamicBytes(channelId)
    val settlementSubstitutions =
        mapOf(
            "TMPL_HYBRID_APP_ID" to appId.toString(),
            "TMPL_CHANNEL_ID" to encodedChannelId.toTealByteLiteral(),
            "TMPL_PAYEE" to decodeAlgorandAddressPublicKey(payeeAddress).toTealByteLiteral(),
            "TMPL_AUTHORIZED_PUBLIC_KEY" to authorizedSignerPublicKey.toTealByteLiteral(),
        )
    val settlementProgramBytes =
        bridge
            .compileTealProgramWithAlgodUrl(
                algodUrl = algodUrl,
                source = renderTealTemplate(SETTLEMENT_LOGIC_SIG_TEAL_TEMPLATE, settlementSubstitutions),
            ).toKotlinByteArray()
    if (settlementProgramBytes.isEmpty()) error("iOS: settlement TEAL compile returned empty")
    val settlementProgramB64 = Base64.encode(settlementProgramBytes)
    val settlementAddress =
        bridge.logicSigAddressWithProgramBase64(programBase64 = settlementProgramB64, argsBase64 = emptyList<String>())
    if (settlementAddress.isEmpty()) error("iOS: failed to derive settlement LogicSig address")
    return settlementAddress
}

@OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)
internal actual suspend fun submitLogicSigSettlementInternal(
    payerSigner: MppWalletSigner,
    appId: Long,
    usdcAssetId: Long,
    algodUrl: String,
    channelId: ByteArray,
    cumulativeAmountMicroUsdc: Long,
    voucherSignature: ByteArray,
    authorizedSignerPublicKey: ByteArray,
    payeeAddress: String,
    note: ByteArray?,
): String {
    require(channelId.size == 32) { "channelId must be 32 bytes" }
    val encodedChannelId = encodeArc4DynamicBytes(channelId)

    val settlementSubstitutions =
        mapOf(
            "TMPL_HYBRID_APP_ID" to appId.toString(),
            "TMPL_CHANNEL_ID" to encodedChannelId.toTealByteLiteral(),
            "TMPL_PAYEE" to decodeAlgorandAddressPublicKey(payeeAddress).toTealByteLiteral(),
            "TMPL_AUTHORIZED_PUBLIC_KEY" to authorizedSignerPublicKey.toTealByteLiteral(),
        )
    val paddingSubstitutions =
        mapOf(
            "TMPL_HYBRID_APP_ID" to appId.toString(),
            "TMPL_CHANNEL_ID" to encodedChannelId.toTealByteLiteral(),
            // The padding LogicSig's teardown sweep returns its unused ALGO fee buffer to
            // whoever funds it, which today is the payer (see fundLogicSigIfNeeded below).
            "TMPL_SWEEP_DESTINATION" to decodeAlgorandAddressPublicKey(payerSigner.address).toTealByteLiteral(),
        )

    // Swift: compileTealProgram(algodUrl:source:) → Kotlin: compileTealProgramWithAlgodUrl
    val settlementProgramBytes =
        bridge
            .compileTealProgramWithAlgodUrl(
                algodUrl = algodUrl,
                source = renderTealTemplate(SETTLEMENT_LOGIC_SIG_TEAL_TEMPLATE, settlementSubstitutions),
            ).toKotlinByteArray()
    if (settlementProgramBytes.isEmpty()) error("iOS: settlement TEAL compile returned empty")
    val paddingProgramBytes =
        bridge
            .compileTealProgramWithAlgodUrl(
                algodUrl = algodUrl,
                source = renderTealTemplate(PADDING_LOGIC_SIG_TEAL_TEMPLATE, paddingSubstitutions),
            ).toKotlinByteArray()
    if (paddingProgramBytes.isEmpty()) error("iOS: padding TEAL compile returned empty")
    val settlementProgramB64 = Base64.encode(settlementProgramBytes)
    val paddingProgramB64 = Base64.encode(paddingProgramBytes)

    val settlementLogicSigArgsB64 = listOf(Base64.encode(voucherSignature), Base64.encode(encodeUint64(cumulativeAmountMicroUsdc)))

    // Swift: logicSigAddress(programBase64:argsBase64:) → Kotlin: logicSigAddressWithProgramBase64
    val settlementAddress =
        bridge.logicSigAddressWithProgramBase64(programBase64 = settlementProgramB64, argsBase64 = settlementLogicSigArgsB64)
    if (settlementAddress.isEmpty()) error("iOS: failed to derive settlement LogicSig address")
    val paddingAddress =
        bridge.logicSigAddressWithProgramBase64(programBase64 = paddingProgramB64, argsBase64 = emptyList<String>())
    if (paddingAddress.isEmpty()) error("iOS: failed to derive padding LogicSig address")

    val channelIdB64 = Base64.encode(channelId)
    val lsigBoxNameB64 = Base64.encode(SETTLEMENT_LOGIC_SIG_BOX_PREFIX + channelId)

    ensureLogicSigSetup(
        payerSigner = payerSigner,
        algodUrl = algodUrl,
        appId = appId,
        channelId = channelId,
        channelIdB64 = channelIdB64,
        lsigBoxNameB64 = lsigBoxNameB64,
        settlementAddress = settlementAddress,
        paddingAddress = paddingAddress,
    )

    val params = fetchTxParams(algodUrl)

    val settlementArgsB64 =
        listOf(SETTLE_FROM_LOGIC_SIG_SELECTOR, encodedChannelId, encodeUint64(cumulativeAmountMicroUsdc)).map { Base64.encode(it) }
    val noteB64 = note?.takeIf { it.isNotEmpty() }?.let { Base64.encode(it) }

    val settlementTxnBytes =
        bridge.buildAppCallTxnWithSenderAddress(
            senderAddress = settlementAddress,
            appId = appId,
            appArgsBase64 = settlementArgsB64,
            boxRefAppIds = listOf(appId, appId),
            boxRefNamesBase64 = listOf(channelIdB64, lsigBoxNameB64),
            foreignAssets = listOf(usdcAssetId),
            foreignAccountAddresses = listOf(payeeAddress),
            fee = LOGIC_SIG_SETTLEMENT_GROUP_FEE,
            firstRound = params.firstRoundValid,
            lastRound = params.lastRoundValid,
            genesisHashBase64 = params.genesisHashBase64,
            genesisID = params.genesisID,
            noteBase64 = noteB64,
        )
    if (settlementTxnBytes.length == 0UL) error("iOS: settlement app-call build returned empty")

    // Swift: buildPaymentTxn(senderAddress:...) → Kotlin: buildPaymentTxnWithSenderAddress
    val paddingTxnBytes =
        bridge.buildPaymentTxnWithSenderAddress(
            senderAddress = paddingAddress,
            receiverAddress = paddingAddress,
            amountMicroAlgo = 0L,
            fee = 0L,
            firstRound = params.firstRoundValid,
            lastRound = params.lastRoundValid,
            genesisHashBase64 = params.genesisHashBase64,
            genesisID = params.genesisID,
            noteBase64 = "",
        )
    if (paddingTxnBytes.length == 0UL) error("iOS: padding payment build returned empty")

    val groupedB64 =
        bridge.assignGroupIdsWithTxnsBase64(
            txnsBase64 =
                listOf(
                    Base64.encode(settlementTxnBytes.toKotlinByteArray()),
                    Base64.encode(paddingTxnBytes.toKotlinByteArray()),
                ),
        )
    if (groupedB64.size < 2) error("iOS: assignGroupIds returned ${groupedB64.size} txns")
    val groupedSettlementB64 = normalizeBase64(groupedB64[0].toString())
    val groupedPaddingB64 = normalizeBase64(groupedB64[1].toString())

    // Swift: signLogicSigTransaction(programBase64:argsBase64:encodedTxBase64:) → Kotlin: signLogicSigTransactionWithProgramBase64
    val signedSettlement =
        bridge
            .signLogicSigTransactionWithProgramBase64(
                programBase64 = settlementProgramB64,
                argsBase64 = settlementLogicSigArgsB64,
                encodedTxBase64 = groupedSettlementB64,
            ).toKotlinByteArray()
    if (signedSettlement.isEmpty()) error("iOS: settlement LogicSig signing failed")
    val signedPadding =
        bridge
            .signLogicSigTransactionWithProgramBase64(
                programBase64 = paddingProgramB64,
                argsBase64 = emptyList<String>(),
                encodedTxBase64 = groupedPaddingB64,
            ).toKotlinByteArray()
    if (signedPadding.isEmpty()) error("iOS: padding LogicSig signing failed")

    val txId = broadcastAndGetTxId(algodUrl, Base64.encode(signedSettlement + signedPadding))
    Napier.d("[iOS_LSIG_SETTLEMENT_OK] txId=$txId appId=$appId cumulativeAmount=$cumulativeAmountMicroUsdc", tag = TAG)
    return txId
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

/**
 * Ensures the channel's settlement LogicSig is registered in the app's box (via a payer-signed
 * app-call) and that both LogicSig accounts (settlement + padding) are funded above the minimum
 * balance plus their settlement-group fee. Mirrors `ensureLogicSigSetup` on Android.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)
private suspend fun ensureLogicSigSetup(
    payerSigner: MppWalletSigner,
    algodUrl: String,
    appId: Long,
    channelId: ByteArray,
    channelIdB64: String,
    lsigBoxNameB64: String,
    settlementAddress: String,
    paddingAddress: String,
) {
    val expectedSettlementPubKey = decodeAlgorandAddressPublicKey(settlementAddress)
    val registeredPubKey =
        runCatching {
            // Swift: syncGetAlgodBox(algodUrl:appId:boxNameBase64:) → Kotlin: syncGetAlgodBoxWithAlgodUrl
            val json = bridge.syncGetAlgodBoxWithAlgodUrl(algodUrl = algodUrl, appId = appId, boxNameBase64 = lsigBoxNameB64)
            if (json.isEmpty()) return@runCatching null
            val valueB64 = parseJsonString(json, "value") ?: return@runCatching null
            Base64.decode(normalizeBase64(valueB64))
        }.getOrNull()

    if (registeredPubKey == null || !registeredPubKey.contentEquals(expectedSettlementPubKey)) {
        // The contract only accepts setSettlementLogicSig from the channel's actual payer
        // (assert Txn.sender === data.payer). If the caller submitting settlement isn't the
        // payer (e.g. the payee auto-settling a viewer's voucher), a self-registration attempt
        // here would always be rejected on-chain — fail fast with a clear, actionable message
        // instead. This also catches the common misuse of passing the wrong
        // authorizedSignerPublicKey (which changes the compiled address and looks like "not
        // registered yet" even when it actually is).
        val channelPayer = runCatching { getChannelPayerAddress(algodUrl, appId, channelIdB64) }.getOrNull()
        check(channelPayer != null && channelPayer == payerSigner.address) {
            "Settlement LogicSig for this channel is not registered on-chain (or was compiled " +
                "with the wrong authorizedSignerPublicKey — expected the channel payer's " +
                "session key). The payer must call setSettlementLogicSig/" +
                "registerSettlementLogicSig with their own signer before settlement can proceed."
        }
        val params = fetchTxParams(algodUrl)
        val regArgsB64 =
            listOf(
                SET_SETTLEMENT_LOGIC_SIG_SELECTOR,
                encodeArc4DynamicBytes(channelId),
                expectedSettlementPubKey,
            ).map { Base64.encode(it) }
        val regTxnBytes =
            bridge.buildAppCallTxnWithSenderAddress(
                senderAddress = payerSigner.address,
                appId = appId,
                appArgsBase64 = regArgsB64,
                boxRefAppIds = listOf(appId, appId),
                boxRefNamesBase64 = listOf(channelIdB64, lsigBoxNameB64),
                foreignAssets = emptyList<Long>(),
                foreignAccountAddresses = emptyList<String>(),
                fee = pooledFeeFor(payerSigner),
                firstRound = params.firstRoundValid,
                lastRound = params.lastRoundValid,
                genesisHashBase64 = params.genesisHashBase64,
                genesisID = params.genesisID,
                noteBase64 = null,
            )
        if (regTxnBytes.length == 0UL) error("iOS: LogicSig registration app-call build returned empty")
        signAndBroadcastSingle(payerSigner, algodUrl, regTxnBytes.toKotlinByteArray())
        Napier.d("[iOS_LSIG_REGISTERED] address=$settlementAddress", tag = TAG)
    }

    fundLogicSigIfNeeded(payerSigner, algodUrl, settlementAddress, LOGIC_SIG_MINIMUM_BALANCE + LOGIC_SIG_SETTLEMENT_GROUP_FEE)
    fundLogicSigIfNeeded(payerSigner, algodUrl, paddingAddress, LOGIC_SIG_MINIMUM_BALANCE)
}

/** Reads the channel box directly to determine the on-chain payer address (first 32 bytes). */
@OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)
private fun getChannelPayerAddress(
    algodUrl: String,
    appId: Long,
    channelIdB64: String,
): String? {
    val json = bridge.syncGetAlgodBoxWithAlgodUrl(algodUrl = algodUrl, appId = appId, boxNameBase64 = channelIdB64)
    if (json.isEmpty()) return null
    val valueB64 = parseJsonString(json, "value") ?: return null
    val bytes = Base64.decode(normalizeBase64(valueB64))
    if (bytes.size < 32) return null
    return encodeAlgorandAddress(bytes.copyOfRange(0, 32))
}

/** Tops up [address] with a payer-signed payment if its balance is below [targetBalance]. */
@OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)
private suspend fun fundLogicSigIfNeeded(
    payerSigner: MppWalletSigner,
    algodUrl: String,
    address: String,
    targetBalance: Long,
) {
    // Swift: syncGetAccountBalance(algodUrl:address:) → Kotlin: syncGetAccountBalanceWithAlgodUrl
    val currentBalance = bridge.syncGetAccountBalanceWithAlgodUrl(algodUrl = algodUrl, address = address)
    val topUpAmount = (targetBalance - currentBalance).coerceAtLeast(0L)
    if (topUpAmount == 0L) return
    val params = fetchTxParams(algodUrl)
    val paymentTxnBytes =
        bridge.buildPaymentTxnWithSenderAddress(
            senderAddress = payerSigner.address,
            receiverAddress = address,
            amountMicroAlgo = topUpAmount,
            fee = pooledFeeFor(payerSigner),
            firstRound = params.firstRoundValid,
            lastRound = params.lastRoundValid,
            genesisHashBase64 = params.genesisHashBase64,
            genesisID = params.genesisID,
            noteBase64 = "",
        )
    if (paymentTxnBytes.length == 0UL) error("iOS: LogicSig funding payment build returned empty")
    signAndBroadcastSingle(payerSigner, algodUrl, paymentTxnBytes.toKotlinByteArray())
    Napier.d("[iOS_LSIG_FUNDED] address=$address topUpMicroAlgos=$topUpAmount targetMicroAlgos=$targetBalance", tag = TAG)
}

/**
 * Signs and broadcasts a single ungrouped transaction. Falcon LogicSig signers bundle-sign
 * through the Go SDK, which transparently adds its own budget "dummy" transactions and assigns
 * the group ID — mirroring the pattern already used for the asset-transfer + app-call group.
 */
@OptIn(ExperimentalEncodingApi::class)
private suspend fun signAndBroadcastSingle(
    signer: MppWalletSigner,
    algodUrl: String,
    txnBytes: ByteArray,
): String {
    val allSignedBytes: ByteArray =
        if (signer.signerType == MppWalletSignerType.FALCON_LSIG) {
            val signedTxns = signer.signTransactionsBytes(listOf(txnBytes))
            if (signedTxns.isEmpty() || signedTxns.all { it.isEmpty() }) error("iOS: Falcon signing failed")
            signedTxns.fold(ByteArray(0)) { acc, b -> acc + b }
        } else {
            signer.signTransactionBytes(txnBytes)
        }
    return broadcastAndGetTxId(algodUrl, Base64.encode(allSignedBytes))
}

/** Fee-pool budget required for a single-transaction submission by [signer]'s signing scheme. */
private fun pooledFeeFor(signer: MppWalletSigner): Long =
    when (signer.signerType) {
        MppWalletSignerType.FALCON_NATIVE, MppWalletSignerType.FALCON_LSIG -> FALCON_SIGNED_TRANSACTION_GROUP_FEE
        MppWalletSignerType.ED25519 -> MIN_TXN_FEE
    }

/** ARC4 dynamic `byte[]` ABI encoding: a 2-byte big-endian length prefix followed by the bytes. */
private fun encodeArc4DynamicBytes(bytes: ByteArray): ByteArray {
    require(bytes.size <= 0xFFFF) { "byte[] too long for ARC4 dynamic bytes" }
    return byteArrayOf(((bytes.size ushr 8) and 0xFF).toByte(), (bytes.size and 0xFF).toByte()) + bytes
}

private const val TEAL_HEX_CHARS = "0123456789abcdef"

/** Renders [this] as a TEAL `0x...` byte literal (avoids `String.format`, unavailable on K/N). */
private fun ByteArray.toTealByteLiteral(): String =
    "0x" +
        joinToString("") {
            val v = it.toInt() and 0xFF
            "${TEAL_HEX_CHARS[v ushr 4]}${TEAL_HEX_CHARS[v and 0xF]}"
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
