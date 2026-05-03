package com.michaeltchuang.walletsdk.core.railmpp.utils

import android.util.Log
import com.algorand.algosdk.crypto.Address
import com.algorand.algosdk.transaction.AppBoxReference
import com.algorand.algosdk.transaction.Transaction
import com.algorand.algosdk.transaction.TxGroup
import com.algorand.algosdk.util.Encoder
import com.algorand.algosdk.v2.client.common.AlgodClient
import com.algorand.algosdk.v2.client.common.Response
import com.algorand.algosdk.v2.client.model.PendingTransactionResponse
import com.algorand.algosdk.v2.client.model.PostTransactionsResponse
import com.michaeltchuang.walletsdk.core.railmpp.MppWalletSigner
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.json.JSONObject
import java.math.BigInteger
import java.security.MessageDigest
import java.security.Security

/**
 * MPP payment helper for Liquid Stream.
 *
 * Note: current implementation supports Algorand and Solana transfer construction/signing.
 */
object MppPayments {
    private const val TAG = "MppPayments"
    private const val DEPOSIT_MICRO_USDC_LONG = 1_000_000L
    private const val COST_PER_BLOCK_MICRO_USDC = 100_000L // 0.1 USDC
    private const val VOUCHER_SETTLE_EVERY_BLOCKS = 3
    private const val CLAIM_VOUCHER_APP_CALL_FEE = 12000L
    private const val VERIFY_HELPER_APP_CALL_FEE = 12000L
    private const val TESTNET_ALGOD_URL = "https://testnet-api.algonode.cloud"
    private val ABI_OPEN_SESSION = byteArrayOf(0x2f, 0xea.toByte(), 0xf2.toByte(), 0x30)
    private val ABI_DEPOSIT = byteArrayOf(0x2f, 0xab.toByte(), 0xb9.toByte(), 0xf2.toByte())
    private val ABI_CLAIM_VOUCHER = byteArrayOf(0x62, 0x4e, 0x66, 0x90.toByte())
    private val ABI_VERIFY_CLAIM_VOUCHER_SIG = byteArrayOf(0x3e, 0xb5.toByte(), 0x7d, 0x95.toByte())

    fun decodeSignedTransaction(bytes: ByteArray): Any? =
        try {
            Encoder.decodeFromMsgPack(bytes, Any::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode signed transaction", e)
            null
        }

    fun createBalanceUpdateJson(
        sessionId: String,
        blocksConsumed: Int,
        remainingMicroUsdc: Long = estimateRemainingUsdcBalanceFromBlocks(blocksConsumed),
    ): String {
        val consumed = blocksConsumed * COST_PER_BLOCK_MICRO_USDC

        return """
            {
                "reference":"liquid:payment:balance",
                "id":"$sessionId",
                "initialDepositMicroUsdc":1000000,
                "consumedMicroUsdc":$consumed,
                "remainingMicroUsdc":$remainingMicroUsdc,
                "blocksWatched":$blocksConsumed,
                "costPerBlockMicroUsdc":100000
            }
            """.trimIndent()
    }

    fun createVoucherJson(
        sessionId: String,
        appId: Long = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
        viewerAddress: String,
        creatorAddress: String,
        blocksConsumed: Int,
        totalAmountUsed: Long,
        remainingMicroUsdc: Long,
    ): String {
        val voucherPayload =
            JSONObject().apply {
                put("reference", "liquid:payment:voucher")
                put("id", sessionId)
                put("appId", appId)
                put("viewer", viewerAddress)
                put("creator", creatorAddress)
                put("blocksWatched", blocksConsumed)
                put("costPerBlockMicroUsdc", COST_PER_BLOCK_MICRO_USDC)
                put("totalAmountClaimedMicroUsdc", totalAmountUsed)
                put("remainingMicroUsdc", remainingMicroUsdc)
            }
        return voucherPayload.toString()
    }

    fun shouldAttemptVoucherSettlement(blocksConsumed: Int): Boolean =
        blocksConsumed > 0 && blocksConsumed % VOUCHER_SETTLE_EVERY_BLOCKS == 0

    fun computeVoucherMicroUsdcUsage(blocksConsumed: Int): Long = (blocksConsumed.toLong() * COST_PER_BLOCK_MICRO_USDC).coerceAtLeast(0L)

    fun voucherSettleWindowMicroUsdc(): Long = (VOUCHER_SETTLE_EVERY_BLOCKS.toLong() * COST_PER_BLOCK_MICRO_USDC).coerceAtLeast(0L)

    fun estimateRemainingUsdcBalanceFromBlocks(blocksConsumed: Int): Long {
        val consumed = blocksConsumed.toLong() * COST_PER_BLOCK_MICRO_USDC
        return (DEPOSIT_MICRO_USDC_LONG - consumed).coerceAtLeast(0L)
    }

    fun remainingUsdcFromMicroAlgos(remainingMicroAlgos: Long): Double = remainingMicroAlgos / 1_000_000.0

    fun maxSessionDepositMicroUsdc(): Long = DEPOSIT_MICRO_USDC_LONG

    fun getRemainingBalanceFromSessionVault(
        viewerAddress: String,
        hostAddress: String,
        appId: Long = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
        algodUrl: String = TESTNET_ALGOD_URL,
    ): Long? =
        runCatching {
            Security.removeProvider("BC")
            Security.insertProviderAt(BouncyCastleProvider(), 0)

            val client = algodClient(algodUrl)
            val boxName = sessionBoxName(viewerAddress, hostAddress)
            val boxNameB64 = Encoder.encodeToBase64(boxName)
            val response =
                client
                    .GetApplicationBoxByName(appId)
                    .name("b64:$boxNameB64")
                    .execute()

            if (!response.isSuccessful) {
                Log.e(
                    TAG,
                    "[SESSION_VAULT_REMAINING_ERR] reason=box_fetch_failed appId=$appId viewer=$viewerAddress host=$hostAddress box=b64:$boxNameB64 code=${response.code()} message=${response.message()}",
                )
                return null
            }

            val sessionBytes = response.body()?.value
            if (sessionBytes == null) {
                Log.e(
                    TAG,
                    "[SESSION_VAULT_REMAINING_ERR] reason=empty_box_value appId=$appId viewer=$viewerAddress host=$hostAddress box=b64:$boxNameB64",
                )
                return null
            }

            decodeRemainingBalanceFromSessionInfo(sessionBytes)
        }.onFailure {
            Log.e(
                TAG,
                "[SESSION_VAULT_REMAINING_ERR] reason=exception appId=$appId viewer=$viewerAddress host=$hostAddress algodUrl=$algodUrl",
                it,
            )
        }.getOrNull()

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
                            AppBoxReference(appId, sessionBoxName(viewerAddress, creatorAddress)),
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
                            Address(creatorAddress).getBytes(),
                            encodeDepositPaymentTuple(
                                assetAmount = depositAmountMicroUsdc,
                                xferAsset = usdcAssetId,
                            ),
                        ),
                    ).foreignAssets(listOf(usdcAssetId))
                    .boxReferences(
                        listOf(
                            AppBoxReference(appId, sessionBoxName(viewerAddress, creatorAddress)),
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
        hostAddress: String,
        totalAmountUsedMicroUsdc: Long,
        signature: ByteArray,
        usdcAssetId: Long = 10458941L,
        algodUrl: String = TESTNET_ALGOD_URL,
    ): Result<String> =
        runCatching {
            val client = algodClient(algodUrl)
            val params = client.TransactionParams().execute().body()
            params.fee = CLAIM_VOUCHER_APP_CALL_FEE
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
                            Address(hostAddress).getBytes(),
                            encodeUint64(totalAmountUsedMicroUsdc),
                            encodeArc4DynamicBytes(signature),
                        ),
                    ).foreignAssets(listOf(usdcAssetId))
                    .boxReferences(
                        listOf(
                            AppBoxReference(appId, sessionBoxName(viewerAddress, hostAddress)),
                        ),
                    ).build()

            appCallTxn.fee = BigInteger.valueOf(CLAIM_VOUCHER_APP_CALL_FEE)
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

    private fun decodeRemainingBalanceFromSessionInfo(bytes: ByteArray): Long {
        if (bytes.size < 48) error("Invalid session box payload size=${bytes.size}")
        val totalDeposit = decodeUint64BigEndian(bytes, 32)
        val lastSettled = decodeUint64BigEndian(bytes, 40)
        return (totalDeposit - lastSettled).coerceAtLeast(0L)
    }

    private fun decodeUint64BigEndian(
        bytes: ByteArray,
        offset: Int,
    ): Long {
        var out = 0L
        for (i in 0 until 8) {
            out = (out shl 8) or (bytes[offset + i].toLong() and 0xFF)
        }
        return out
    }

    private fun algodClient(url: String): AlgodClient {
        val clean = url.removeSuffix("/")
        val uri = java.net.URI(clean)
        val host = uri.host ?: error("Invalid algod host: $url")
        val scheme = uri.scheme ?: "https"
        val port =
            if (uri.port == -1) {
                if (scheme == "https") {
                    443
                } else {
                    80
                }
            } else {
                uri.port
            }
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

    private fun sessionBoxName(
        viewerAddress: String,
        hostAddress: String,
    ): ByteArray {
        val viewerKey = Address(viewerAddress).getBytes()
        val hostKey = Address(hostAddress).getBytes()
        return viewerKey + hostKey
    }

    fun buildClaimMessage(
        appId: Long,
        totalAmountClaimedMicroUsdc: Long,
    ): ByteArray {
        val appBytes =
            java.nio.ByteBuffer
                .allocate(8)
                .putLong(appId)
                .array()
        val amountBytes =
            java.nio.ByteBuffer
                .allocate(8)
                .putLong(totalAmountClaimedMicroUsdc)
                .array()
        return appBytes + amountBytes + "settle".toByteArray()
    }

    fun serializeVoucherSignature(signature: ByteArray): String =
        java.util.Base64
            .getEncoder()
            .encodeToString(signature)

    fun buildClaimMessageHashHex(
        appId: Long,
        totalAmountClaimedMicroUsdc: Long,
    ): String {
        val message = buildClaimMessage(appId, totalAmountClaimedMicroUsdc)
        val digest = MessageDigest.getInstance("SHA-256").digest(message)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun signClaimMessageWithAlgoSdk(
        secretKey: ByteArray,
        appId: Long,
        totalAmountClaimedMicroUsdc: Long,
    ): ByteArray? =
        runCatching {
            val message = buildClaimMessage(appId, totalAmountClaimedMicroUsdc)
            val normalizedSecretKey =
                when (secretKey.size) {
                    32 -> secretKey
                    64 -> secretKey.copyOfRange(0, 32)
                    else -> error("Unsupported secret key size=${secretKey.size}")
                }

            // Avoid Account(...) construction here on Android (can throw Conscrypt EdDSA key-size errors).
            val signer = Ed25519Signer()
            signer.init(true, Ed25519PrivateKeyParameters(normalizedSecretKey, 0))
            signer.update(message, 0, message.size)
            signer.generateSignature()
        }.onFailure {
            Log.e(
                TAG,
                "[CLAIM_SIGN_ALGOSDK_ERR] appId=$appId totalAmountClaimedMicroUsdc=$totalAmountClaimedMicroUsdc keySize=${secretKey.size}",
                it,
            )
        }.getOrNull()

    fun hashHex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun verifyClaimSignatureLocally(
        viewerAddress: String,
        appId: Long,
        totalAmountClaimedMicroUsdc: Long,
        signature: ByteArray,
    ): Boolean =
        runCatching {
            val message = buildClaimMessage(appId, totalAmountClaimedMicroUsdc)
            val publicKey = Address(viewerAddress).getBytes()
            if (publicKey.size != 32 || signature.size != 64) return false

            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
            verifier.update(message, 0, message.size)
            verifier.verifySignature(signature)
        }.getOrDefault(false)

    data class VerifyClaimVoucherOnChainResult(
        val txId: String,
        val verified: Boolean,
        val confirmedRound: Long,
        val logCount: Int,
    )

    suspend fun debugVerifyClaimVoucherSignatureOnChain(
        signer: MppWalletSigner,
        appId: Long,
        viewerAddress: String,
        totalAmountClaimedMicroUsdc: Long,
        signature: ByteArray,
        algodUrl: String = TESTNET_ALGOD_URL,
    ): Result<VerifyClaimVoucherOnChainResult> =
        runCatching {
            val client = algodClient(algodUrl)
            val params = client.TransactionParams().execute().body()
            params.fee = VERIFY_HELPER_APP_CALL_FEE

            val encodedSig = encodeArc4DynamicBytes(signature)
            val selectorHex = ABI_VERIFY_CLAIM_VOUCHER_SIG.joinToString("") { "%02x".format(it) }
            val localVerify =
                verifyClaimSignatureLocally(
                    viewerAddress = viewerAddress,
                    appId = appId,
                    totalAmountClaimedMicroUsdc = totalAmountClaimedMicroUsdc,
                    signature = signature,
                )
            Log.e(
                TAG,
                "[VERIFY_HELPER_BUILD] appId=$appId sender=${signer.address} viewer=$viewerAddress amount=$totalAmountClaimedMicroUsdc selector=$selectorHex sigLen=${signature.size} arc4SigLen=${encodedSig.size} localVerify=$localVerify",
            )

            val appCallTxn =
                Transaction
                    .ApplicationCallTransactionBuilder()
                    .sender(signer.address)
                    .suggestedParams(params)
                    .applicationId(appId)
                    .args(
                        listOf(
                            ABI_VERIFY_CLAIM_VOUCHER_SIG,
                            Address(viewerAddress).getBytes(),
                            encodeUint64(totalAmountClaimedMicroUsdc),
                            encodedSig,
                        ),
                    ).build()

            appCallTxn.fee = BigInteger.valueOf(VERIFY_HELPER_APP_CALL_FEE)
            val signedAppCall = signer.signTransaction(appCallTxn)
            Log.e(
                TAG,
                "[VERIFY_HELPER_BROADCAST_ATTEMPT] appId=$appId sender=${signer.address} viewer=$viewerAddress amount=$totalAmountClaimedMicroUsdc txId=${appCallTxn.txID()}",
            )
            val txId = broadcastGroup(client, listOf(signedAppCall)) ?: appCallTxn.txID()
            val pending = waitForPendingTransaction(client, txId)
            VerifyClaimVoucherOnChainResult(
                txId = txId,
                verified = true,
                confirmedRound = pending?.confirmedRound ?: 0L,
                logCount = pending?.logs?.size ?: 0,
            )
        }.onFailure {
            Log.e(
                TAG,
                "[VERIFY_HELPER_BROADCAST_ERR] appId=$appId sender=${signer.address} viewer=$viewerAddress amount=$totalAmountClaimedMicroUsdc",
                it,
            )
        }

    private fun waitForPendingTransaction(
        client: AlgodClient,
        txId: String,
        maxRounds: Int = 8,
    ): PendingTransactionResponse? {
        var last: PendingTransactionResponse? = null
        repeat(maxRounds) {
            val pendingResp = client.PendingTransactionInformation(txId).execute()
            if (!pendingResp.isSuccessful) return last
            val body = pendingResp.body()
            last = body
            val confirmedRound = body?.confirmedRound ?: 0L
            if (confirmedRound > 0L) return body
            Thread.sleep(700)
        }
        return last
    }
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
