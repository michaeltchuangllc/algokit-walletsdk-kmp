package com.michaeltchuang.walletsdk.core.railmpp.internal

import android.util.Log
import com.algorand.algosdk.crypto.Address
import com.algorand.algosdk.transaction.AppBoxReference
import com.algorand.algosdk.transaction.Transaction
import com.algorand.algosdk.transaction.TxGroup
import com.algorand.algosdk.util.Encoder
import com.algorand.algosdk.v2.client.common.AlgodClient
import com.algorand.algosdk.v2.client.common.Response
import com.algorand.algosdk.v2.client.model.PostTransactionsResponse
import com.michaeltchuang.walletsdk.core.railmpp.AndroidMppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSigner
import java.math.BigInteger
import java.net.URI

private const val TAG = "AlgorandOps"
private const val APP_CALL_FEE = 12_000L
private const val SIGNER_TYPE_FALCON = 1L

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
    defaultSalt: ByteArray,
    algodUrl: String,
    args: List<ByteArray>,
    boxKeys: List<Pair<Long, ByteArray>>,
    foreignAssets: List<Long>,
    foreignAccounts: List<String>,
): String {
    BouncyCastleProviderSetup.ensure()
    val client = algodClient(algodUrl)
    val params = client.TransactionParams().execute().body()
    val appCallTxn = buildAppCallTxn(signer, params, appId, args, boxKeys.toBoxReferences(), foreignAssets, foreignAccounts)

    val useFalcon = signer.signerType == SIGNER_TYPE_FALCON
    val txns = listOf(appCallTxn)
    if (!useFalcon) {
        TxGroup.assignGroupID(*txns.toTypedArray())
    }
    Log.d(TAG, "[APP_CALL_PRE_SIGN] sender=${signer.address} appId=$appId txCount=${txns.size} falcon=$useFalcon")

    val signed = signTxnGroup(signer, txns)
    require(if (useFalcon) signed.size >= txns.size else signed.size == txns.size) {
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

    val useFalcon = signer.signerType == SIGNER_TYPE_FALCON
    val txns = listOf(axferTxn, appCallTxn)
    if (!useFalcon) {
        TxGroup.assignGroupID(*txns.toTypedArray())
    }
    Log.d(TAG, "[OPEN_TOPUP_PRE_SIGN] sender=${signer.address} appId=$appId txCount=${txns.size} falcon=$useFalcon")

    val signed = signTxnGroup(signer, txns)
    require(if (useFalcon) signed.size >= txns.size else signed.size == txns.size) {
        "Unexpected signed group size: ${signed.size}, expected ${txns.size}"
    }
    return broadcast(client, signed) ?: appCallTxn.txID()
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
 * Signs transaction objects directly. Falcon signers bundle-sign ungrouped real transactions
 * and let the Falcon mobile SDK add verification-budget dummies and assign the group.
 */
private suspend fun signTxnGroup(
    signer: MppWalletSigner,
    txns: List<Transaction>,
): List<ByteArray> =
    when (signer) {
        is AndroidMppWalletSigner -> signer.signTransactions(txns)
        else -> txns.map { signer.signTransactionBytes(Encoder.encodeToMsgPack(it)) }
    }

private fun buildAppCallTxn(
    signer: MppWalletSigner,
    params: com.algorand.algosdk.v2.client.model.TransactionParametersResponse,
    appId: Long,
    args: List<ByteArray>,
    boxReferences: List<AppBoxReference>,
    foreignAssets: List<Long>,
    foreignAccounts: List<String> = emptyList(),
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
    return builder.build().also { it.fee = BigInteger.valueOf(APP_CALL_FEE) }
}


private fun List<Pair<Long, ByteArray>>.toBoxReferences(): List<AppBoxReference> = map { (id, key) -> AppBoxReference(id, key) }

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
    if (!resp.isSuccessful) error("Broadcast failed: ${resp.message() ?: "unknown"}")
    return resp.body()?.txId
}
