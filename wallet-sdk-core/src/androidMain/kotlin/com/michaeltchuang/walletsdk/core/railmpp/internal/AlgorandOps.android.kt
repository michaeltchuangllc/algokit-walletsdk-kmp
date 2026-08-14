package com.michaeltchuang.walletsdk.core.railmpp.internal

import android.util.Base64
import android.util.Log
import com.algorand.algosdk.account.LogicSigAccount
import com.algorand.algosdk.crypto.Address
import com.algorand.algosdk.transaction.AppBoxReference
import com.algorand.algosdk.transaction.Transaction
import com.algorand.algosdk.transaction.TxGroup
import com.algorand.algosdk.util.Encoder
import com.algorand.algosdk.v2.client.common.AlgodClient
import com.algorand.algosdk.v2.client.common.Response
import com.algorand.algosdk.v2.client.model.PostTransactionsResponse
import com.michaeltchuang.walletsdk.core.network.domain.AndroidContextHolder
import com.michaeltchuang.walletsdk.core.railmpp.AndroidMppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSignerType
import com.michaeltchuang.walletsdk.core.utils.GoMobileDispatcher
import io.github.algorandecosystem.sdk.Sdk
import java.math.BigInteger
import java.net.URI

private const val TAG = "AlgorandOps"
private const val APP_CALL_FEE = 12_000L
private const val DUMMIES_PER_REAL_TXN = 3
private const val MIN_TXN_FEE = 1_000L

// Hardened LogicSig programs add encoded-byte fee on Futurenet; the teardown-sweep
// branch grew the settlement LogicSig program, bumping this from 18 to 30 microAlgos.
private const val LOGIC_SIG_SETTLEMENT_GROUP_FEE = 3_030L
private const val LOGIC_SIG_MINIMUM_BALANCE = 100_000L
private const val FALCON_SIGNED_TRANSACTION_GROUP_FEE =
    MIN_TXN_FEE * (DUMMIES_PER_REAL_TXN + 1)
private const val SETTLEMENT_TEMPLATE_ASSET = "railmpp/EscrowSessionSettlementLogicSig.teal"
private const val PADDING_TEMPLATE_ASSET = "railmpp/EscrowSessionSettlementPaddingLogicSig.teal"

private val SETTLE_FROM_LOGIC_SIG_SELECTOR = byteArrayOf(0x43, 0x9c.toByte(), 0x5f, 0xb1.toByte())
private val SETTLEMENT_LOGIC_SIG_BOX_PREFIX = "l".encodeToByteArray()

private val falconLsigAddress: Address by lazy {
    Address(GoMobileDispatcher.runOnGoThread { Sdk.getFalconLsigAddress() })
}

internal actual fun getSessionBoxBytesInternal(
    appId: Long,
    channelId: ByteArray,
    algodUrl: String,
): ByteArray {
    val client = algodClient(algodUrl)
    val boxNameB64 = Encoder.encodeToBase64(channelId)
    val response = client.GetApplicationBoxByName(appId).name("b64:$boxNameB64").execute()
    if (!response.isSuccessful) error("Box fetch failed: ${response.message() ?: "unknown"}")
    return response.body()?.value ?: error("Empty box value")
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
    val client = algodClient(algodUrl)
    val params = client.TransactionParams().execute().body()
    val appCallTxn = buildAppCallTxn(signer, params, appId, args, boxKeys.toBoxReferences(), foreignAssets, foreignAccounts, note)

    val needsFalcon24Dummies = signer.signerType == MppWalletSignerType.FALCON_LSIG
    val dummies = if (needsFalcon24Dummies) List(DUMMIES_PER_REAL_TXN) { buildFalconDummy(params, it) } else emptyList()
    if (dummies.isNotEmpty()) {
        appCallTxn.fee = BigInteger.valueOf((appCallTxn.fee?.toLong() ?: MIN_TXN_FEE) + MIN_TXN_FEE * dummies.size)
    }

    val txns = dummies + appCallTxn
    TxGroup.assignGroupID(*txns.toTypedArray())
    Log.d(
        TAG,
        "[APP_CALL_PRE_SIGN] sender=${signer.address} appId=$appId txCount=${txns.size} " +
            "signerType=${signer.signerType} falcon24Dummies=$needsFalcon24Dummies",
    )

    val signed = signTxnGroup(signer, txns)
    require(if (needsFalcon24Dummies) signed.size >= txns.size else signed.size == txns.size) {
        "Unexpected signed group size: ${signed.size}, expected ${txns.size}"
    }
    return broadcast(client, signed) ?: appCallTxn.txID()
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
    val client = algodClient(algodUrl)
    val params = client.TransactionParams().execute().body()

    val axferTxn =
        Transaction
            .AssetTransferTransactionBuilder()
            .sender(Address(signer.address))
            .assetReceiver(Address.forApplication(appId))
            .assetAmount(depositAmountMicroUsdc)
            .assetIndex(usdcAssetId)
            .suggestedParams(params)
            .build()

    val appCallTxn = buildAppCallTxn(signer, params, appId, appCallArgs, boxKeys.toBoxReferences(), appCallForeignAssets)

    val needsFalcon24Dummies = signer.signerType == MppWalletSignerType.FALCON_LSIG
    val realTxns = listOf(axferTxn, appCallTxn)
    val txns =
        if (needsFalcon24Dummies) {
            val dummies = List(2 * DUMMIES_PER_REAL_TXN) { buildFalconDummy(params, it) }
            axferTxn.fee = BigInteger.valueOf((axferTxn.fee?.toLong() ?: MIN_TXN_FEE) + MIN_TXN_FEE * dummies.size)
            dummies + realTxns
        } else {
            realTxns
        }
    TxGroup.assignGroupID(*txns.toTypedArray())
    Log.d(
        TAG,
        "[OPEN_TOPUP_PRE_SIGN] sender=${signer.address} appId=$appId txCount=${txns.size} " +
            "signerType=${signer.signerType} falcon24Dummies=$needsFalcon24Dummies",
    )
    val signed = signTxnGroup(signer, txns)
    require(if (needsFalcon24Dummies) signed.size >= txns.size else signed.size == txns.size) {
        "Unexpected signed group size: ${signed.size}, expected ${realTxns.size}"
    }
    return broadcast(client, signed) ?: appCallTxn.txID()
}

internal actual suspend fun compileSettlementLogicSigAddressInternal(
    appId: Long,
    algodUrl: String,
    channelId: ByteArray,
    authorizedSignerPublicKey: ByteArray,
    payeeAddress: String,
): String {
    val client = algodClient(algodUrl)
    val settlementProgram = compileSettlementProgram(client, appId, channelId, payeeAddress, authorizedSignerPublicKey)
    return LogicSigAccount(settlementProgram, emptyList()).address.toString()
}

private fun compileSettlementProgram(
    client: AlgodClient,
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
            "TMPL_PAYEE" to Address(payeeAddress).getBytes().toTealByteLiteral(),
            "TMPL_AUTHORIZED_PUBLIC_KEY" to authorizedSignerPublicKey.toTealByteLiteral(),
        )
    return client.compileTeal(renderTealTemplate(SETTLEMENT_TEMPLATE_ASSET, substitutions))
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
    val client = algodClient(algodUrl)
    val encodedChannelId = byteArrayOf(0, channelId.size.toByte()) + channelId
    val settlementProgram = compileSettlementProgram(client, appId, channelId, payeeAddress, authorizedSignerPublicKey)
    val paddingProgram =
        client.compileTeal(
            renderTealTemplate(
                PADDING_TEMPLATE_ASSET,
                mapOf(
                    "TMPL_HYBRID_APP_ID" to appId.toString(),
                    "TMPL_CHANNEL_ID" to encodedChannelId.toTealByteLiteral(),
                    // The padding LogicSig's teardown sweep returns its unused ALGO fee buffer to
                    // whoever funds it, which today is the payer (see fundLogicSigIfNeeded below).
                    "TMPL_SWEEP_DESTINATION" to Address(payerSigner.address).getBytes().toTealByteLiteral(),
                ),
            ),
        )
    val settlementLogicSig =
        LogicSigAccount(
            settlementProgram,
            listOf(voucherSignature, encodeUint64(cumulativeAmountMicroUsdc)),
        )
    val paddingLogicSig = LogicSigAccount(paddingProgram, emptyList())
    ensureLogicSigSetup(
        client = client,
        payerSigner = payerSigner,
        appId = appId,
        channelId = channelId,
        settlementLogicSig = settlementLogicSig,
        paddingLogicSig = paddingLogicSig,
    )
    val params = client.TransactionParams().execute().body()
    val settlementTxn =
        Transaction
            .ApplicationCallTransactionBuilder()
            .sender(settlementLogicSig.address)
            .suggestedParams(params)
            .applicationId(appId)
            .args(
                listOf(
                    SETTLE_FROM_LOGIC_SIG_SELECTOR,
                    encodedChannelId,
                    encodeUint64(cumulativeAmountMicroUsdc),
                ),
            ).accounts(listOf(Address(payeeAddress)))
            .boxReferences(
                listOf(
                    AppBoxReference(appId, channelId),
                    AppBoxReference(appId, SETTLEMENT_LOGIC_SIG_BOX_PREFIX + channelId),
                ),
            ).foreignAssets(listOf(usdcAssetId))
            .build()
            .also {
                it.fee = BigInteger.valueOf(LOGIC_SIG_SETTLEMENT_GROUP_FEE)
                if (note != null && note.isNotEmpty()) it.note = note
            }
    val paddingTxn =
        Transaction
            .PaymentTransactionBuilder()
            .sender(paddingLogicSig.address)
            .receiver(paddingLogicSig.address)
            .amount(0)
            .suggestedParams(params)
            .build()
            .also { it.fee = BigInteger.ZERO }
    TxGroup.assignGroupID(settlementTxn, paddingTxn)
    val signedSettlement = settlementLogicSig.signLogicSigTransaction(settlementTxn)
    val signedPadding = paddingLogicSig.signLogicSigTransaction(paddingTxn)
    val txId = broadcast(client, listOf(Encoder.encodeToMsgPack(signedSettlement), Encoder.encodeToMsgPack(signedPadding)))
    Log.d(TAG, "[LSIG_SETTLEMENT_OK] txId=$txId appId=$appId cumulativeAmount=$cumulativeAmountMicroUsdc")
    return txId ?: settlementTxn.txID()
}

internal actual fun decodeMsgPackAny(bytes: ByteArray): Any? = runCatching { Encoder.decodeFromMsgPack(bytes, Any::class.java) }.getOrNull()

internal actual fun awaitConfirmationDetailsInternal(
    txId: String,
    algodUrl: String,
    maxRounds: Int,
): Pair<Long, Int> {
    val client = algodClient(algodUrl)
    var last: Pair<Long, Int> = Pair(0L, 0)
    repeat(maxRounds) {
        val resp = client.PendingTransactionInformation(txId).execute()
        if (!resp.isSuccessful) return last
        val body = resp.body() ?: return last
        val round = body.confirmedRound ?: 0L
        val logs = body.logs?.size ?: 0
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

/**
 * Signs a transaction group passing [Transaction] objects directly to avoid
 * Jackson's @JsonInclude(NON_DEFAULT) omitting fee=0 from the dummy msgpack bytes.
 * Falcon signers override signTransactions to bundle-sign the whole group at once.
 */
private suspend fun signTxnGroup(
    signer: MppWalletSigner,
    txns: List<Transaction>,
): List<ByteArray> =
    when (signer) {
        is AndroidMppWalletSigner -> signer.signTransactions(txns)
        else -> signer.signTransactionsBytes(txns.map { Encoder.encodeToMsgPack(it) })
    }

private fun buildAppCallTxn(
    signer: MppWalletSigner,
    params: com.algorand.algosdk.v2.client.model.TransactionParametersResponse,
    appId: Long,
    args: List<ByteArray>,
    boxReferences: List<AppBoxReference>,
    foreignAssets: List<Long>,
    foreignAccounts: List<String> = emptyList(),
    note: ByteArray? = null,
): Transaction {
    val builder =
        Transaction
            .ApplicationCallTransactionBuilder()
            .sender(signer.address)
            .suggestedParams(params)
            .applicationId(appId)
            .args(args)
    if (boxReferences.isNotEmpty()) builder.boxReferences(boxReferences)
    if (foreignAssets.isNotEmpty()) builder.foreignAssets(foreignAssets)
    if (foreignAccounts.isNotEmpty()) builder.accounts(foreignAccounts.map { Address(it) })
    if (note != null && note.isNotEmpty()) builder.note(note)
    return builder.build().also { it.fee = BigInteger.valueOf(APP_CALL_FEE) }
}

private fun buildFalconDummy(
    params: com.algorand.algosdk.v2.client.model.TransactionParametersResponse,
    index: Int,
): Transaction =
    Transaction
        .PaymentTransactionBuilder()
        .sender(falconLsigAddress)
        .receiver(falconLsigAddress)
        .amount(0)
        .suggestedParams(params)
        .note(byteArrayOf(index.toByte()))
        .build()
        .also { it.fee = BigInteger.ZERO }

private fun List<Pair<Long, ByteArray>>.toBoxReferences(): List<AppBoxReference> = map { (id, key) -> AppBoxReference(id, key) }

/** Reads the channel box directly to determine the on-chain payer address (first 32 bytes). */
private fun getChannelPayerAddress(
    client: AlgodClient,
    appId: Long,
    channelId: ByteArray,
): String? {
    val bytes =
        client
            .GetApplicationBoxByName(appId)
            .name("b64:${Encoder.encodeToBase64(channelId)}")
            .execute()
            .body()
            ?.value
            ?: return null
    if (bytes.size < 32) return null
    return encodeAlgorandAddress(bytes.copyOfRange(0, 32))
}

private suspend fun ensureLogicSigSetup(
    client: AlgodClient,
    payerSigner: MppWalletSigner,
    appId: Long,
    channelId: ByteArray,
    settlementLogicSig: LogicSigAccount,
    paddingLogicSig: LogicSigAccount,
) {
    val settlementAddress = settlementLogicSig.address
    val paddingAddress = paddingLogicSig.address
    val registeredAddress =
        runCatching {
            client
                .GetApplicationBoxByName(appId)
                .name("b64:${Encoder.encodeToBase64(SETTLEMENT_LOGIC_SIG_BOX_PREFIX + channelId)}")
                .execute()
                .body()
                ?.value
                ?.takeIf { it.size == Address.LEN_BYTES }
        }.getOrNull()
    if (registeredAddress == null || !registeredAddress.contentEquals(settlementAddress.getBytes())) {
        // The contract only accepts setSettlementLogicSig from the channel's actual payer
        // (assert Txn.sender === data.payer). If the caller submitting settlement isn't the
        // payer (e.g. the payee auto-settling a viewer's voucher), a self-registration attempt
        // here would always be rejected on-chain — fail fast with a clear, actionable message
        // instead. This also catches the common misuse of passing the wrong
        // authorizedSignerPublicKey (which changes the compiled address and looks like "not
        // registered yet" even when it actually is).
        val channelPayer =
            runCatching { getChannelPayerAddress(client, appId, channelId) }.getOrNull()
        check(channelPayer != null && channelPayer == payerSigner.address) {
            "Settlement LogicSig for this channel is not registered on-chain (or was compiled " +
                "with the wrong authorizedSignerPublicKey — expected the channel payer's " +
                "session key). The payer must call setSettlementLogicSig/" +
                "registerSettlementLogicSig with their own signer before settlement can proceed."
        }
        val params = client.TransactionParams().execute().body()
        val registrationTxn =
            buildAppCallTxn(
                signer = payerSigner,
                params = params,
                appId = appId,
                args = listOf(SET_SETTLEMENT_LOGIC_SIG_SELECTOR, encodeArc4DynamicBytes(channelId), settlementAddress.getBytes()),
                boxReferences =
                    listOf(
                        AppBoxReference(appId, channelId),
                        AppBoxReference(appId, SETTLEMENT_LOGIC_SIG_BOX_PREFIX + channelId),
                    ),
                foreignAssets = emptyList(),
            ).also { transaction ->
                transaction.fee = BigInteger.valueOf(pooledFeeFor(payerSigner))
            }
        val signed = signTxnGroup(payerSigner, listOf(registrationTxn))
        broadcast(client, signed)
        Log.d(TAG, "[LSIG_REGISTERED] address=$settlementAddress")
    }
    fundLogicSigIfNeeded(client, payerSigner, settlementAddress, LOGIC_SIG_MINIMUM_BALANCE + LOGIC_SIG_SETTLEMENT_GROUP_FEE)
    fundLogicSigIfNeeded(client, payerSigner, paddingAddress, LOGIC_SIG_MINIMUM_BALANCE)
}

private suspend fun fundLogicSigIfNeeded(
    client: AlgodClient,
    payerSigner: MppWalletSigner,
    address: Address,
    targetBalance: Long,
) {
    val currentBalance =
        runCatching {
            client
                .AccountInformation(address)
                .execute()
                .body()
                ?.amount ?: 0L
        }.getOrDefault(0L)
    val topUpAmount = (targetBalance - currentBalance).coerceAtLeast(0L)
    if (topUpAmount == 0L) return
    val params = client.TransactionParams().execute().body()
    val paymentTxn =
        Transaction
            .PaymentTransactionBuilder()
            .sender(payerSigner.address)
            .receiver(address)
            .amount(topUpAmount)
            .suggestedParams(params)
            .build()
            .also { transaction ->
                transaction.fee = BigInteger.valueOf(pooledFeeFor(payerSigner))
            }
    val signed = signTxnGroup(payerSigner, listOf(paymentTxn))
    broadcast(client, signed)
    Log.d(TAG, "[LSIG_FUNDED] address=$address topUpMicroAlgos=$topUpAmount targetMicroAlgos=$targetBalance")
}

private fun pooledFeeFor(signer: MppWalletSigner): Long =
    when (signer.signerType) {
        MppWalletSignerType.FALCON_NATIVE,
        MppWalletSignerType.FALCON_LSIG,
        -> FALCON_SIGNED_TRANSACTION_GROUP_FEE
        MppWalletSignerType.ED25519 -> MIN_TXN_FEE
    }

private fun encodeArc4DynamicBytes(bytes: ByteArray): ByteArray {
    require(bytes.size <= 0xFFFF) { "byte[] too long for ARC4 dynamic bytes" }
    return byteArrayOf(((bytes.size ushr 8) and 0xFF).toByte(), (bytes.size and 0xFF).toByte()) + bytes
}

private val SET_SETTLEMENT_LOGIC_SIG_SELECTOR = byteArrayOf(0x42, 0xd9.toByte(), 0x75, 0xa6.toByte())

private fun AlgodClient.compileTeal(teal: String): ByteArray {
    val response = TealCompile().source(teal.encodeToByteArray()).execute()
    if (!response.isSuccessful) error("LogicSig TEAL compilation failed: ${response.message() ?: "unknown"}")
    val result = response.body()?.result ?: error("LogicSig TEAL compilation returned no program")
    return Base64.decode(result, Base64.DEFAULT)
}

private fun renderTealTemplate(
    assetPath: String,
    substitutions: Map<String, String>,
): String {
    val context = AndroidContextHolder.applicationContext ?: error("Android application context is required to load $assetPath")
    var template =
        context.assets
            .open(assetPath)
            .bufferedReader()
            .use { it.readText() }
    substitutions.forEach { (name, value) -> template = template.replace(name, value) }
    require(!TEMPLATE_VARIABLE_PATTERN.containsMatchIn(template)) { "Unresolved LogicSig template variables in $assetPath" }
    return template
}

private fun ByteArray.toTealByteLiteral(): String = "0x" + joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }

private val TEMPLATE_VARIABLE_PATTERN = Regex("TMPL_[A-Z0-9_]+")

private fun algodClient(url: String): AlgodClient {
    val uri = URI(url.removeSuffix("/"))
    val scheme = uri.scheme ?: "https"
    val port = if (uri.port == -1) (if (scheme == "https") 443 else 80) else uri.port
    return AlgodClient("$scheme://${uri.host}", port, "")
}

private fun broadcast(
    client: AlgodClient,
    signedBlobs: List<ByteArray>,
): String? {
    val concatenated = signedBlobs.fold(ByteArray(0)) { acc, b -> acc + b }
    val resp: Response<PostTransactionsResponse> = client.RawTransaction().rawtxn(concatenated).execute()
    if (!resp.isSuccessful) {
        val responseBody = resp.body()?.toString().orEmpty()
        val reason = responseBody.ifBlank { resp.message() ?: "unknown" }
        Log.e(TAG, "[ALGORAND_GROUP_REJECTED] txCount=${signedBlobs.size} reason=$reason")
        error("Broadcast failed: $reason")
    }
    return resp.body()?.txId
}
