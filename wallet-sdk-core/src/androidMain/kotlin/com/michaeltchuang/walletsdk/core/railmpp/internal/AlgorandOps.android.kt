package com.michaeltchuang.walletsdk.core.railmpp.internal

import android.util.Base64
import android.util.Log
import com.michaeltchuang.walletsdk.core.network.domain.AndroidContextHolder
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSignerType
import com.michaeltchuang.walletsdk.core.utils.GoMobileDispatcher
import io.github.algorandecosystem.sdk.Sdk
import org.json.JSONObject
import uniffi.algokit_transact_ffi.AppCallTransactionFields
import uniffi.algokit_transact_ffi.AssetTransferTransactionFields
import uniffi.algokit_transact_ffi.BoxReference
import uniffi.algokit_transact_ffi.LogicSignature
import uniffi.algokit_transact_ffi.OnApplicationComplete
import uniffi.algokit_transact_ffi.PaymentTransactionFields
import uniffi.algokit_transact_ffi.SignedTransaction
import uniffi.algokit_transact_ffi.Transaction
import uniffi.algokit_transact_ffi.TransactionType
import uniffi.algokit_transact_ffi.encodeSignedTransaction
import uniffi.algokit_transact_ffi.encodeTransactionRaw
import uniffi.algokit_transact_ffi.getLogicSignatureAddress
import uniffi.algokit_transact_ffi.getTransactionId
import uniffi.algokit_transact_ffi.groupTransactions
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val TAG = "AlgorandOps"
private const val SETTLEMENT_TEMPLATE_ASSET = "railmpp/EscrowSessionSettlementLogicSig.teal"
private const val PADDING_TEMPLATE_ASSET = "railmpp/EscrowSessionSettlementPaddingLogicSig.teal"
private const val HTTP_TIMEOUT_MS = 15_000

// Shared fee/selector/box-prefix constants live in commonMain's AlgorandUtils.kt
// (APP_CALL_FEE, MIN_TXN_FEE, DUMMIES_PER_REAL_TXN, FALCON_SIGNED_TRANSACTION_GROUP_FEE,
// LOGIC_SIG_SETTLEMENT_GROUP_FEE, LOGIC_SIG_MINIMUM_BALANCE, SETTLE_FROM_LOGIC_SIG_SELECTOR,
// SET_SETTLEMENT_LOGIC_SIG_SELECTOR, SETTLEMENT_LOGIC_SIG_BOX_PREFIX) — same package, no import needed.

private val falconLsigAddress: String by lazy {
    GoMobileDispatcher.runOnGoThread { Sdk.getFalconLsigAddress() }
}

// ── Expect implementations ──────────────────────────────────────────────────

internal actual fun getSessionBoxBytesInternal(
    appId: Long,
    channelId: ByteArray,
    algodUrl: String,
): ByteArray {
    val boxNameB64 = Base64.encodeToString(channelId, Base64.NO_WRAP)
    return algodGetBoxValue(algodUrl, appId, boxNameB64) ?: error("Box fetch failed")
}

internal actual fun simulateReadonlyMethodInternal(
    appId: Long,
    algodUrl: String,
    selector: ByteArray,
    args: List<ByteArray>,
    boxKeys: List<Pair<Long, ByteArray>>,
): ByteArray? =
    try {
        val params = fetchTxParams(algodUrl)
        // Readonly ABI getters don't need a real sender — algod's `allow-empty-signatures` +
        // `allow-unnamed-resources` let the app's own address stand in.
        val txn =
            buildAppCallTxn(
                sender = appIdToAlgorandAddress(appId),
                appId = appId,
                args = listOf(selector) + args,
                boxReferences = boxKeys,
                foreignAssets = emptyList(),
                foreignAccounts = emptyList(),
                fee = MIN_TXN_FEE,
                params = params,
            )
        val requestBytes = buildSimulateRequestMsgpack(encodeTransactionRaw(txn))
        val response = httpRequest(algodUrl, "/v2/transactions/simulate", "POST", "application/msgpack", requestBytes)
        if (response.code !in 200..299) {
            Log.w(TAG, "[SIMULATE_READONLY_HTTP_ERROR] appId=$appId reason=${response.body.take(300)}")
            return null
        }
        val json = JSONObject(response.body)
        val groupResult = json.optJSONArray("txn-groups")?.optJSONObject(0) ?: return null
        val failureMessage = groupResult.optString("failure-message", "")
        if (failureMessage.isNotEmpty()) {
            Log.w(TAG, "[SIMULATE_READONLY_FAILED] appId=$appId reason=$failureMessage")
            return null
        }
        val logsJson =
            groupResult
                .optJSONArray("txn-results")
                ?.optJSONObject(0)
                ?.optJSONObject("txn-result")
                ?.optJSONArray("logs")
        val logs =
            (0 until (logsJson?.length() ?: 0)).mapNotNull { i ->
                logsJson?.optString(i)?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() }
            }
        val returnLog =
            logs.lastOrNull {
                it.size >= ABI_RETURN_LOG_PREFIX.size &&
                    it.copyOfRange(0, ABI_RETURN_LOG_PREFIX.size).contentEquals(ABI_RETURN_LOG_PREFIX)
            } ?: return null
        returnLog.copyOfRange(ABI_RETURN_LOG_PREFIX.size, returnLog.size)
    } catch (t: Throwable) {
        Log.w(TAG, "[SIMULATE_READONLY_ERROR] appId=$appId error=${t.message}")
        null
    }

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
    BouncyCastleProviderSetup.ensure()
    val params = fetchTxParams(algodUrl)
    val appCallTxn =
        buildAppCallTxn(signer.address, appId, args, boxKeys, foreignAssets, foreignAccounts, APP_CALL_FEE, params, note)

    val needsFalcon24Dummies = signer.signerType == MppWalletSignerType.FALCON_LSIG
    val dummies = if (needsFalcon24Dummies) List(DUMMIES_PER_REAL_TXN) { buildFalconDummy(params, it) } else emptyList()
    val feePaddedAppCallTxn =
        if (dummies.isEmpty()) {
            appCallTxn
        } else {
            appCallTxn.copy(fee = (appCallTxn.fee ?: MIN_TXN_FEE.toULong()) + (MIN_TXN_FEE * dummies.size).toULong())
        }

    val txns = groupTransactions(dummies + feePaddedAppCallTxn)
    Log.d(
        TAG,
        "[APP_CALL_PRE_SIGN] sender=${signer.address} appId=$appId txCount=${txns.size} " +
            "signerType=${signer.signerType} falcon24Dummies=$needsFalcon24Dummies",
    )

    val signed = signTxnGroup(signer, txns)
    require(if (needsFalcon24Dummies) signed.size >= txns.size else signed.size == txns.size) {
        "Unexpected signed group size: ${signed.size}, expected ${txns.size}"
    }
    return broadcast(algodUrl, signed) ?: getTransactionId(txns.last())
}

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
    BouncyCastleProviderSetup.ensure()
    val params = fetchTxParams(algodUrl)

    val axferTxn =
        buildAssetTransferTxn(
            sender = signer.address,
            receiver = appIdToAlgorandAddress(appId),
            assetId = usdcAssetId,
            amount = depositAmountMicroUsdc,
            fee = MIN_TXN_FEE,
            params = params,
        )
    val appCallTxn =
        buildAppCallTxn(signer.address, appId, appCallArgs, boxKeys, appCallForeignAssets, emptyList(), APP_CALL_FEE, params)

    val needsFalcon24Dummies = signer.signerType == MppWalletSignerType.FALCON_LSIG
    val realTxns = listOf(axferTxn, appCallTxn)
    val txns =
        if (needsFalcon24Dummies) {
            val dummies = List(2 * DUMMIES_PER_REAL_TXN) { buildFalconDummy(params, it) }
            val feePaddedAxferTxn = axferTxn.copy(fee = (axferTxn.fee ?: MIN_TXN_FEE.toULong()) + (MIN_TXN_FEE * dummies.size).toULong())
            groupTransactions(dummies + listOf(feePaddedAxferTxn, appCallTxn))
        } else {
            groupTransactions(realTxns)
        }
    Log.d(
        TAG,
        "[OPEN_TOPUP_PRE_SIGN] sender=${signer.address} appId=$appId txCount=${txns.size} " +
            "signerType=${signer.signerType} falcon24Dummies=$needsFalcon24Dummies",
    )
    val signed = signTxnGroup(signer, txns)
    require(if (needsFalcon24Dummies) signed.size >= txns.size else signed.size == txns.size) {
        "Unexpected signed group size: ${signed.size}, expected ${realTxns.size}"
    }
    return broadcast(algodUrl, signed) ?: getTransactionId(txns.last())
}

internal actual suspend fun compileSettlementLogicSigAddressInternal(
    appId: Long,
    algodUrl: String,
    channelId: ByteArray,
    authorizedSignerPublicKey: ByteArray,
    payeeAddress: String,
): String {
    val settlementProgram = compileSettlementProgram(algodUrl, appId, channelId, payeeAddress, authorizedSignerPublicKey)
    // AlgoKitTransact (algokit-core Rust library) instead of the Java SDK's LogicSigAccount —
    // matches the iOS bridge, which uses the same Rust core for LogicSig address derivation.
    return getLogicSignatureAddress(settlementProgram)
}

private fun compileSettlementProgram(
    algodUrl: String,
    appId: Long,
    channelId: ByteArray,
    payeeAddress: String,
    authorizedSignerPublicKey: ByteArray,
): ByteArray {
    require(channelId.size == 32) { "channelId must be 32 bytes" }
    val encodedChannelId = byteArrayOf(0, channelId.size.toByte()) + channelId
    val substitutions =
        mapOf(
            "TMPL_HYBRID_APP_ID" to appId.toString(),
            "TMPL_CHANNEL_ID" to encodedChannelId.toTealByteLiteral(),
            "TMPL_PAYEE" to decodeAlgorandAddressPublicKey(payeeAddress).toTealByteLiteral(),
            "TMPL_AUTHORIZED_PUBLIC_KEY" to authorizedSignerPublicKey.toTealByteLiteral(),
        )
    return algodCompileTeal(algodUrl, renderTealTemplate(SETTLEMENT_TEMPLATE_ASSET, substitutions))
}

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
    val encodedChannelId = byteArrayOf(0, channelId.size.toByte()) + channelId
    val settlementProgram = compileSettlementProgram(algodUrl, appId, channelId, payeeAddress, authorizedSignerPublicKey)
    val paddingProgram =
        algodCompileTeal(
            algodUrl,
            renderTealTemplate(
                PADDING_TEMPLATE_ASSET,
                mapOf(
                    "TMPL_HYBRID_APP_ID" to appId.toString(),
                    "TMPL_CHANNEL_ID" to encodedChannelId.toTealByteLiteral(),
                    // The padding LogicSig's teardown sweep returns its unused ALGO fee buffer to
                    // whoever funds it, which today is the payer (see fundLogicSigIfNeeded below).
                    "TMPL_SWEEP_DESTINATION" to decodeAlgorandAddressPublicKey(payerSigner.address).toTealByteLiteral(),
                ),
            ),
        )
    val settlementLogicSigArgs = listOf(voucherSignature, encodeUint64(cumulativeAmountMicroUsdc))
    val settlementAddress = getLogicSignatureAddress(settlementProgram)
    val paddingAddress = getLogicSignatureAddress(paddingProgram)
    ensureLogicSigSetup(
        algodUrl = algodUrl,
        payerSigner = payerSigner,
        appId = appId,
        channelId = channelId,
        settlementAddress = settlementAddress,
        paddingAddress = paddingAddress,
    )
    val params = fetchTxParams(algodUrl)
    val settlementTxn =
        buildAppCallTxn(
            sender = settlementAddress,
            appId = appId,
            args = listOf(SETTLE_FROM_LOGIC_SIG_SELECTOR, encodedChannelId, encodeUint64(cumulativeAmountMicroUsdc)),
            boxReferences = listOf(appId to channelId, appId to (SETTLEMENT_LOGIC_SIG_BOX_PREFIX + channelId)),
            foreignAssets = listOf(usdcAssetId),
            foreignAccounts = listOf(payeeAddress),
            fee = LOGIC_SIG_SETTLEMENT_GROUP_FEE,
            params = params,
            note = note,
        )
    val paddingTxn = buildPaymentTxn(paddingAddress, paddingAddress, 0L, 0L, params)
    val (groupedSettlement, groupedPadding) = groupTransactions(listOf(settlementTxn, paddingTxn))
    val signedSettlement = signWithLogicSig(settlementProgram, settlementLogicSigArgs, groupedSettlement)
    val signedPadding = signWithLogicSig(paddingProgram, emptyList(), groupedPadding)
    val txId = broadcast(algodUrl, listOf(signedSettlement, signedPadding))
    Log.d(TAG, "[LSIG_SETTLEMENT_OK] txId=$txId appId=$appId cumulativeAmount=$cumulativeAmountMicroUsdc")
    return txId ?: getTransactionId(groupedSettlement)
}

/**
 * Signs [unsignedTxn] as an escrow LogicSig using AlgoKitTransact (algokit-core Rust library) —
 * the same core iOS uses for this operation — wrapping it with the compiled [program] + [args]
 * as a [LogicSignature] and encoding it as a fully signed transaction ready to broadcast.
 */
private fun signWithLogicSig(
    program: ByteArray,
    args: List<ByteArray>,
    unsignedTxn: Transaction,
): ByteArray {
    val logicSignature = LogicSignature(logic = program, args = args.ifEmpty { null })
    val signed = SignedTransaction(transaction = unsignedTxn, logicSignature = logicSignature)
    return encodeSignedTransaction(signed)
}

internal actual fun decodeMsgPackAny(bytes: ByteArray): Any? = null

internal actual fun awaitConfirmationDetailsInternal(
    txId: String,
    algodUrl: String,
    maxRounds: Int,
): Pair<Long, Int> {
    var last: Pair<Long, Int> = Pair(0L, 0)
    repeat(maxRounds) {
        val json = httpGetJson(algodUrl, "/v2/transactions/pending/$txId") ?: return last
        val round = json.optLong("confirmed-round", 0L)
        val logs = json.optJSONArray("logs")?.length() ?: 0
        last = Pair(round, logs)
        if (round > 0L) return last
        Thread.sleep(700)
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

// ── Private helpers ───────────────────────────────────────────────────────────

private data class AlgodTxParams(
    val firstValid: Long,
    val lastValid: Long,
    val genesisHash: ByteArray,
    val genesisId: String,
    val minFee: Long,
)

private fun fetchTxParams(algodUrl: String): AlgodTxParams {
    val json = httpGetJson(algodUrl, "/v2/transactions/params") ?: error("Failed to fetch suggested params from $algodUrl")
    val lastRound = json.optLong("last-round", -1L).takeIf { it >= 0 } ?: error("Missing last-round in suggested params")
    val genesisHashB64 = json.optString("genesis-hash", "").takeIf { it.isNotEmpty() } ?: error("Missing genesis-hash in suggested params")
    return AlgodTxParams(
        firstValid = lastRound,
        lastValid = lastRound + 1000L,
        genesisHash = Base64.decode(genesisHashB64, Base64.DEFAULT),
        genesisId = json.optString("genesis-id", "testnet-v1.0"),
        minFee = json.optLong("min-fee", MIN_TXN_FEE),
    )
}

private fun buildPaymentTxn(
    sender: String,
    receiver: String,
    amount: Long,
    fee: Long,
    params: AlgodTxParams,
): Transaction =
    Transaction(
        transactionType = TransactionType.PAYMENT,
        sender = sender,
        fee = fee.toULong(),
        firstValid = params.firstValid.toULong(),
        lastValid = params.lastValid.toULong(),
        genesisHash = params.genesisHash,
        genesisId = params.genesisId,
        payment = PaymentTransactionFields(receiver = receiver, amount = amount.toULong()),
    )

private fun buildAssetTransferTxn(
    sender: String,
    receiver: String,
    assetId: Long,
    amount: Long,
    fee: Long,
    params: AlgodTxParams,
): Transaction =
    Transaction(
        transactionType = TransactionType.ASSET_TRANSFER,
        sender = sender,
        fee = fee.toULong(),
        firstValid = params.firstValid.toULong(),
        lastValid = params.lastValid.toULong(),
        genesisHash = params.genesisHash,
        genesisId = params.genesisId,
        assetTransfer = AssetTransferTransactionFields(assetId = assetId.toULong(), amount = amount.toULong(), receiver = receiver),
    )

private fun buildAppCallTxn(
    sender: String,
    appId: Long,
    args: List<ByteArray>,
    boxReferences: List<Pair<Long, ByteArray>>,
    foreignAssets: List<Long>,
    foreignAccounts: List<String>,
    fee: Long,
    params: AlgodTxParams,
    note: ByteArray? = null,
): Transaction =
    Transaction(
        transactionType = TransactionType.APP_CALL,
        sender = sender,
        fee = fee.toULong(),
        firstValid = params.firstValid.toULong(),
        lastValid = params.lastValid.toULong(),
        genesisHash = params.genesisHash,
        genesisId = params.genesisId,
        note = note?.takeIf { it.isNotEmpty() },
        appCall =
            AppCallTransactionFields(
                appId = appId.toULong(),
                onComplete = OnApplicationComplete.NO_OP,
                args = args.ifEmpty { null },
                accountReferences = foreignAccounts.ifEmpty { null },
                assetReferences = foreignAssets.map { it.toULong() }.ifEmpty { null },
                boxReferences = boxReferences.map { (id, name) -> BoxReference(appId = id.toULong(), name = name) }.ifEmpty { null },
            ),
    )

private fun buildFalconDummy(
    params: AlgodTxParams,
    index: Int,
): Transaction = buildPaymentTxn(falconLsigAddress, falconLsigAddress, 0L, 0L, params).copy(note = byteArrayOf(index.toByte()))

/**
 * Signs a transaction group by handing canonical (domain-prefix-free) MsgPack bytes to the
 * signer — the same wire format [com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSigner]
 * expects everywhere else in the SDK.
 */
private suspend fun signTxnGroup(
    signer: MppWalletSigner,
    txns: List<Transaction>,
): List<ByteArray> = signer.signTransactionsBytes(txns.map { encodeTransactionRaw(it) })

/** Reads the channel box directly to determine the on-chain payer address (first 32 bytes). */
private fun getChannelPayerAddress(
    algodUrl: String,
    appId: Long,
    channelId: ByteArray,
): String? {
    val bytes = algodGetBoxValue(algodUrl, appId, Base64.encodeToString(channelId, Base64.NO_WRAP)) ?: return null
    return decodeChannelPayerAddress(bytes)
}

private suspend fun ensureLogicSigSetup(
    algodUrl: String,
    payerSigner: MppWalletSigner,
    appId: Long,
    channelId: ByteArray,
    settlementAddress: String,
    paddingAddress: String,
) {
    val settlementAddressBytes = decodeAlgorandAddressPublicKey(settlementAddress)
    val registeredAddress =
        runCatching {
            algodGetBoxValue(algodUrl, appId, Base64.encodeToString(SETTLEMENT_LOGIC_SIG_BOX_PREFIX + channelId, Base64.NO_WRAP))
                ?.takeIf { it.size == settlementAddressBytes.size }
        }.getOrNull()
    if (registeredAddress == null || !registeredAddress.contentEquals(settlementAddressBytes)) {
        // The contract only accepts setSettlementLogicSig from the channel's actual payer
        // (assert Txn.sender === data.payer). If the caller submitting settlement isn't the
        // payer (e.g. the payee auto-settling a viewer's voucher), a self-registration attempt
        // here would always be rejected on-chain — fail fast with a clear, actionable message
        // instead. This also catches the common misuse of passing the wrong
        // authorizedSignerPublicKey (which changes the compiled address and looks like "not
        // registered yet" even when it actually is).
        val channelPayer =
            runCatching { getChannelPayerAddress(algodUrl, appId, channelId) }.getOrNull()
        check(channelPayer != null && channelPayer == payerSigner.address) {
            "Settlement LogicSig for this channel is not registered on-chain (or was compiled " +
                "with the wrong authorizedSignerPublicKey — expected the channel payer's " +
                "session key). The payer must call setSettlementLogicSig/" +
                "registerSettlementLogicSig with their own signer before settlement can proceed."
        }
        val params = fetchTxParams(algodUrl)
        val registrationTxn =
            buildAppCallTxn(
                sender = payerSigner.address,
                appId = appId,
                args = listOf(SET_SETTLEMENT_LOGIC_SIG_SELECTOR, encodeArc4DynamicBytes(channelId), settlementAddressBytes),
                boxReferences = listOf(appId to channelId, appId to (SETTLEMENT_LOGIC_SIG_BOX_PREFIX + channelId)),
                foreignAssets = emptyList(),
                foreignAccounts = emptyList(),
                fee = pooledFeeFor(payerSigner.signerType),
                params = params,
            )
        val signed = signTxnGroup(payerSigner, listOf(registrationTxn))
        broadcast(algodUrl, signed)
        Log.d(TAG, "[LSIG_REGISTERED] address=$settlementAddress")
    }
    fundLogicSigIfNeeded(algodUrl, payerSigner, settlementAddress, LOGIC_SIG_MINIMUM_BALANCE + LOGIC_SIG_SETTLEMENT_GROUP_FEE)
    fundLogicSigIfNeeded(algodUrl, payerSigner, paddingAddress, LOGIC_SIG_MINIMUM_BALANCE)
}

private suspend fun fundLogicSigIfNeeded(
    algodUrl: String,
    payerSigner: MppWalletSigner,
    address: String,
    targetBalance: Long,
) {
    val currentBalance = algodGetAccountBalance(algodUrl, address)
    val topUpAmount = (targetBalance - currentBalance).coerceAtLeast(0L)
    if (topUpAmount == 0L) return
    val params = fetchTxParams(algodUrl)
    val paymentTxn = buildPaymentTxn(payerSigner.address, address, topUpAmount, pooledFeeFor(payerSigner.signerType), params)
    val signed = signTxnGroup(payerSigner, listOf(paymentTxn))
    broadcast(algodUrl, signed)
    Log.d(TAG, "[LSIG_FUNDED] address=$address topUpMicroAlgos=$topUpAmount targetMicroAlgos=$targetBalance")
}

// pooledFeeFor, encodeArc4DynamicBytes, and the settlement/box-prefix selector constants now
// live in commonMain's AlgorandUtils.kt (shared with iOS) — same package, no import needed.

/** Loads the asset text and delegates `TMPL_*` substitution to the shared commonMain helper. */
private fun renderTealTemplate(
    assetPath: String,
    substitutions: Map<String, String>,
): String {
    val context = AndroidContextHolder.applicationContext ?: error("Android application context is required to load $assetPath")
    val template =
        context.assets
            .open(assetPath)
            .bufferedReader()
            .use { it.readText() }
    return runCatching { substituteTealTemplate(template, substitutions) }
        .getOrElse { error("Unresolved LogicSig template variables in $assetPath") }
}

// toTealByteLiteral() now lives in commonMain's AlgorandUtils.kt (shared with iOS).

// ── Minimal algod REST client (replaces the Java algosdk's AlgodClient/Encoder/Transaction) ──

private data class HttpResponse(
    val code: Int,
    val body: String,
)

private fun httpRequest(
    algodUrl: String,
    path: String,
    method: String = "GET",
    contentType: String? = null,
    body: ByteArray? = null,
): HttpResponse {
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
        HttpResponse(code, text.orEmpty())
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

private fun algodGetBoxValue(
    algodUrl: String,
    appId: Long,
    boxNameB64: String,
): ByteArray? {
    val encodedName = URLEncoder.encode("b64:$boxNameB64", "UTF-8")
    val valueB64 = httpGetJson(algodUrl, "/v2/applications/$appId/box?name=$encodedName")?.optString("value", "")
    return valueB64?.takeIf { it.isNotEmpty() }?.let { Base64.decode(it, Base64.DEFAULT) }
}

private fun algodGetAccountBalance(
    algodUrl: String,
    address: String,
): Long = runCatching { httpGetJson(algodUrl, "/v2/accounts/$address")?.optLong("amount", 0L) ?: 0L }.getOrDefault(0L)

private fun algodCompileTeal(
    algodUrl: String,
    source: String,
): ByteArray {
    val response = httpRequest(algodUrl, "/v2/teal/compile", "POST", "text/plain", source.toByteArray(Charsets.UTF_8))
    if (response.code !in
        200..299
    ) {
        error("LogicSig TEAL compilation failed: ${response.body.take(300).ifBlank { "HTTP ${response.code}" }}")
    }
    val resultB64 =
        runCatching { JSONObject(response.body).optString("result", "") }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: error("LogicSig TEAL compilation returned no program")
    return Base64.decode(resultB64, Base64.DEFAULT)
}

private fun broadcast(
    algodUrl: String,
    signedBlobs: List<ByteArray>,
): String? {
    val concatenated = signedBlobs.fold(ByteArray(0)) { acc, b -> acc + b }
    val response = httpRequest(algodUrl, "/v2/transactions", "POST", "application/x-binary", concatenated)
    if (response.code !in 200..299) {
        Log.e(TAG, "[ALGORAND_GROUP_REJECTED] txCount=${signedBlobs.size} reason=${response.body.take(300)}")
        error("Broadcast failed: ${response.body.ifBlank { "HTTP ${response.code}" }}")
    }
    return runCatching { JSONObject(response.body).optString("txId", "").takeIf { it.isNotEmpty() } }.getOrNull()
}
