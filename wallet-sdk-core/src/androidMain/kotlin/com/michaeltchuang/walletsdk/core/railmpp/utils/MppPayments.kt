package com.michaeltchuang.walletsdk.core.railmpp.utils

import android.util.Log
import com.algorand.algosdk.crypto.Address
import com.algorand.algosdk.transaction.AppBoxReference
import com.algorand.algosdk.transaction.SignedTransaction
import com.algorand.algosdk.transaction.Transaction
import com.algorand.algosdk.transaction.TxGroup
import com.algorand.algosdk.util.Encoder
import com.algorand.algosdk.v2.client.common.AlgodClient
import com.algorand.algosdk.v2.client.common.Response
import com.algorand.algosdk.v2.client.model.PostTransactionsResponse
import com.michaeltchuang.walletsdk.core.railmpp.MppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.usecases.GetSessionVaultRemainingBalanceUseCase
import org.json.JSONObject

/**
 * MPP payment helper for Liquid Stream.
 *
 * Note: current implementation supports Algorand and Solana transfer construction/signing.
 */
object MppPayments {
    private const val TAG = "MppPayments"
    private const val DEPOSIT_MICRO_USDC_LONG = 1_000_000L
    private const val COST_PER_BLOCK_MICRO_USDC = 100_000L // 0.1 USDC
    private const val VOUCHER_SETTLE_EVERY_BLOCKS = 2
    private const val TESTNET_ALGOD_URL = "https://testnet-api.algonode.cloud"
    private val ABI_OPEN_SESSION = byteArrayOf(0x2f, 0xea.toByte(), 0xf2.toByte(), 0x30)
    private val ABI_DEPOSIT = byteArrayOf(0x1f, 0x90.toByte(), 0xff.toByte(), 0xfd.toByte())
    private val ABI_CLAIM_VOUCHER = byteArrayOf(0x9d.toByte(), 0xbf.toByte(), 0x62, 0x04)

    fun decodeSignedTransaction(bytes: ByteArray): SignedTransaction? =
        try {
            Encoder.decodeFromMsgPack(bytes, SignedTransaction::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode signed transaction", e)
            null
        }

    fun createBalanceUpdateJson(
        sessionId: String,
        blocksConsumed: Int,
        remainingMicroUsdc: Long = estimateRemainingFromBlocks(blocksConsumed),
    ): String {
        val consumed = blocksConsumed * COST_PER_BLOCK_MICRO_USDC

        return """{"reference":"liquid:payment:balance","id":"$sessionId","initialDepositMicroUsdc":1000000,"consumedMicroUsdc":$consumed,"remainingMicroUsdc":$remainingMicroUsdc,"blocksWatched":$blocksConsumed,"costPerBlockMicroUsdc":100000}"""
    }

    fun createVoucherJson(
        sessionId: String,
        appId: Long = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
        viewerAddress: String,
        creatorAddress: String,
        blocksConsumed: Int,
        totalAmountClaimed: Long,
        remainingMicroUsdc: Long,
    ): String {
        val voucherPayload = JSONObject().apply {
            put("reference", "liquid:payment:voucher")
            put("id", sessionId)
            put("appId", appId)
            put("viewer", viewerAddress)
            put("creator", creatorAddress)
            put("blocksWatched", blocksConsumed)
            put("costPerBlockMicroUsdc", COST_PER_BLOCK_MICRO_USDC)
            put("totalAmountClaimedMicroUsdc", totalAmountClaimed)
            put("remainingMicroUsdc", remainingMicroUsdc)
        }
        return voucherPayload.toString()
    }

    fun shouldAttemptVoucherSettlement(blocksConsumed: Int): Boolean =
        blocksConsumed > 0 && blocksConsumed % VOUCHER_SETTLE_EVERY_BLOCKS == 0

    fun computeVoucherClaimedMicroAlgos(blocksConsumed: Int): Long =
        (blocksConsumed.toLong() * COST_PER_BLOCK_MICRO_USDC).coerceAtLeast(0L)

    fun estimateRemainingFromBlocks(blocksConsumed: Int): Long {
        val consumed = blocksConsumed.toLong() * COST_PER_BLOCK_MICRO_USDC
        return (DEPOSIT_MICRO_USDC_LONG - consumed).coerceAtLeast(0L)
    }

    fun remainingUsdcFromMicroAlgos(remainingMicroAlgos: Long): Double = remainingMicroAlgos / 1_000_000.0

    private val getSessionVaultRemainingBalanceUseCase = GetSessionVaultRemainingBalanceUseCase()

    fun getRemainingBalanceFromSessionVault(
        viewerAddress: String,
        appId: Long = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
        algodUrl: String = TESTNET_ALGOD_URL,
    ): Long? = getSessionVaultRemainingBalanceUseCase(
        viewerAddress = viewerAddress,
        appId = appId,
        algodUrl = algodUrl,
    )

    suspend fun openSessionAndDeposit(
        signer: MppWalletSigner,
        viewerAddress: String,
        creatorAddress: String,
        depositAmountMicroUsdc: Long = DEPOSIT_MICRO_USDC_LONG,
        appId: Long = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
        usdcAssetId: Long = 10458941L,
        algodUrl: String = TESTNET_ALGOD_URL,
    ): Result<String> =
        runCatching {
            val client = algodClient(algodUrl)
            val params = client.TransactionParams().execute().body()

            val openSessionTxn =
                Transaction
                    .ApplicationCallTransactionBuilder()
                    .sender(signer.address)
                    .suggestedParams(params)
                    .applicationId(appId)
                    .args(
                        listOf(
                            ABI_OPEN_SESSION,
                            Address(viewerAddress).getBytes(),
                            Address(creatorAddress).getBytes(),
                        ),
                    ).boxReferences(
                        listOf(
                            AppBoxReference(appId, sessionBoxName(viewerAddress)),
                        ),
                    ).build()

            val depositAxferTxn =
                Transaction
                    .AssetTransferTransactionBuilder()
                    .sender(signer.address)
                    .suggestedParams(params)
                    .assetReceiver(RailMppConstants.MPP_SESSION_VAULT_APP_ADDRESS)
                    .assetAmount(depositAmountMicroUsdc)
                    .assetIndex(usdcAssetId)
                    .build()

            val depositAppCallTxn =
                Transaction
                    .ApplicationCallTransactionBuilder()
                    .sender(signer.address)
                    .suggestedParams(params)
                    .applicationId(appId)
                    .args(
                        listOf(
                            ABI_DEPOSIT,
                            Address(viewerAddress).getBytes(),
                            encodeDepositPaymentTuple(
                                assetAmount = depositAmountMicroUsdc,
                                xferAsset = usdcAssetId,
                            ),
                        ),
                    ).foreignAssets(listOf(usdcAssetId))
                    .boxReferences(
                        listOf(
                            AppBoxReference(appId, sessionBoxName(viewerAddress)),
                        ),
                    ).build()

            TxGroup.assignGroupID(openSessionTxn, depositAxferTxn, depositAppCallTxn)

            val signedOpen = signer.signTransaction(openSessionTxn)
            val signedDepositAxfer = signer.signTransaction(depositAxferTxn)
            val signedDepositCall = signer.signTransaction(depositAppCallTxn)

            val txId = broadcastGroup(client, listOf(signedOpen, signedDepositAxfer, signedDepositCall))
            txId ?: openSessionTxn.txID()
        }

    suspend fun claimVoucher(
        signer: MppWalletSigner,
        appId: Long,
        viewerAddress: String,
        totalAmountClaimedMicroUsdc: Long,
        signature: ByteArray,
        usdcAssetId: Long = 10458941L,
        algodUrl: String = TESTNET_ALGOD_URL,
    ): Result<String> =
        runCatching {
            val client = algodClient(algodUrl)
            val params = client.TransactionParams().execute().body()
            val appCallTxn =
                Transaction
                    .ApplicationCallTransactionBuilder()
                    .sender(signer.address)
                    .suggestedParams(params)
                    .applicationId(appId)
                    .args(
                        listOf(
                            ABI_CLAIM_VOUCHER,
                            Address(viewerAddress).getBytes(),
                            encodeUint64(totalAmountClaimedMicroUsdc),
                            encodeArc4DynamicBytes(signature),
                        ),
                    ).foreignAssets(listOf(usdcAssetId))
                    .boxReferences(
                        listOf(
                            AppBoxReference(appId, sessionBoxName(viewerAddress)),
                        ),
                    ).build()

            val signedAppCall = signer.signTransaction(appCallTxn)
            val txId = broadcastGroup(client, listOf(signedAppCall))
            txId ?: appCallTxn.txID()
        }

    private fun encodeDepositPaymentTuple(
        assetAmount: Long,
        xferAsset: Long,
    ): ByteArray {
        val receiverBytes = Address(RailMppConstants.MPP_SESSION_VAULT_APP_ADDRESS).getBytes()
        return receiverBytes + encodeUint64(assetAmount) + encodeUint64(xferAsset)
    }

    private fun encodeUint64(value: Long): ByteArray =
        java.nio.ByteBuffer
            .allocate(8)
            .putLong(value)
            .array()

    private fun encodeArc4DynamicBytes(bytes: ByteArray): ByteArray {
        require(bytes.size <= 0xFFFF) { "signature too long for ARC4 byte[] encoding" }
        val lengthPrefix =
            byteArrayOf(
                ((bytes.size ushr 8) and 0xFF).toByte(),
                (bytes.size and 0xFF).toByte(),
            )
        return lengthPrefix + bytes
    }

    private fun algodClient(url: String): AlgodClient {
        val clean = url.removeSuffix("/")
        val uri = java.net.URI(clean)
        val host = uri.host ?: error("Invalid algod host: $url")
        val scheme = uri.scheme ?: "https"
        val port = if (uri.port == -1) if (scheme == "https") 443 else 80 else uri.port
        return AlgodClient("$scheme://$host", port, "")
    }

    private fun broadcastGroup(
        client: AlgodClient,
        signedBlobs: List<ByteArray>,
    ): String? {
        val concatenated = signedBlobs.fold(ByteArray(0)) { acc, b -> acc + b }
        val resp: Response<PostTransactionsResponse> = client.RawTransaction().rawtxn(concatenated).execute()
        if (!resp.isSuccessful) {
            val err = resp.message() ?: "algod rejected the group"
            error("SessionVault broadcast failed: $err")
        }
        return resp.body()?.txId
    }

    private fun sessionBoxName(viewerAddress: String): ByteArray {
        val publicKey = Address(viewerAddress).getBytes()
        return byteArrayOf('s'.code.toByte()) + publicKey
    }

    fun buildClaimMessage(
        appId: Long,
        totalAmountClaimedMicroUsdc: Long,
    ): ByteArray {
        val appBytes = java.nio.ByteBuffer.allocate(8).putLong(appId).array()
        val amountBytes = java.nio.ByteBuffer.allocate(8).putLong(totalAmountClaimedMicroUsdc).array()
        return appBytes + amountBytes + "settle".toByteArray()
    }

    fun serializeVoucherSignature(signature: ByteArray): String = java.util.Base64.getEncoder().encodeToString(signature)
}


sealed class MppPaymentVerificationResult {
    data class Valid(
        val senderAddress: String,
        val signedTransactionBytes: ByteArray,
        val transactionId: String,
    ) : MppPaymentVerificationResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Valid

            if (senderAddress != other.senderAddress) return false
            if (!signedTransactionBytes.contentEquals(other.signedTransactionBytes)) return false
            if (transactionId != other.transactionId) return false

            return true
        }

        override fun hashCode(): Int {
            var result = senderAddress.hashCode()
            result = 31 * result + signedTransactionBytes.contentHashCode()
            result = 31 * result + transactionId.hashCode()
            return result
        }
    }

    data class Invalid(
        val reason: String,
    ) : MppPaymentVerificationResult()
}
