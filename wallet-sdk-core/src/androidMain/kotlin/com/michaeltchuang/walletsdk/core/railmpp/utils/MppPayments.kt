package com.michaeltchuang.walletsdk.core.railmpp.utils

import android.util.Log
import com.algorand.algosdk.crypto.Address
import com.algorand.algosdk.transaction.AppBoxReference
import com.algorand.algosdk.transaction.Transaction
import com.algorand.algosdk.util.Encoder
import com.algorand.algosdk.v2.client.common.AlgodClient
import com.algorand.algosdk.v2.client.common.Response
import com.algorand.algosdk.v2.client.model.PendingTransactionResponse
import com.algorand.algosdk.v2.client.model.PostTransactionsResponse
import com.michaeltchuang.walletsdk.core.deeplink.utils.AssetConstants
import com.michaeltchuang.walletsdk.core.railmpp.MppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.smartcontract.EscrowSessionVaultManagerClient
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.json.JSONObject
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.Security

/**
 * MPP payment helper for Liquid Stream.
 *
 * Note: current implementation supports Algorand and Solana transfer construction/signing.
 */
object MppPayments {
    private const val TAG = "MppPayments"

    private fun contractClient(
        appId: Long = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
        usdcAssetId: Long = AssetConstants.USDC_TESTNET_ID,
        algodUrl: String = TESTNET_ALGOD_URL,
    ): EscrowSessionVaultManagerClient =
        EscrowSessionVaultManagerClient(
            appId = appId,
            usdcAssetId = usdcAssetId,
            defaultSalt = CHANNEL_ID_SALT,
            defaultAlgodUrl = algodUrl,
        )

    init {
        ensureBouncyCastleProvider()
    }

    private const val DEPOSIT_MICRO_USDC_LONG = 1_000_000L
    private const val COST_PER_BLOCK_MICRO_USDC = 100_000L // 0.1 USDC
    private const val VOUCHER_SETTLE_EVERY_BLOCKS = 3
    private const val VERIFY_HELPER_APP_CALL_FEE = 12000L
    private const val TESTNET_ALGOD_URL = "https://testnet-api.algonode.cloud"
    private val CHANNEL_ID_SALT = "walletsdk-session-v1".toByteArray(StandardCharsets.UTF_8)
    private val ABI_VERIFY_SETTLE_SIGNATURE = byteArrayOf(0x27, 0x04, 0x92.toByte(), 0x89.toByte())

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
    ): Long {
        ensureBouncyCastleProvider()
        val baseContext = "viewer=$viewerAddress host=$hostAddress"
        val channelIdCandidates =
            listOf(
                deriveChannelId(viewerAddress, hostAddress, authorizedSignerAddress = viewerAddress, salt = CHANNEL_ID_SALT),
                deriveChannelId(viewerAddress, hostAddress, authorizedSignerAddress = hostAddress, salt = CHANNEL_ID_SALT),
            ).distinctBy { Encoder.encodeToBase64(it) }

        channelIdCandidates.forEachIndexed { index, channelId ->
            val result =
                getRemainingBalanceFromSessionVaultByChannelId(
                    channelId = channelId,
                    appId = appId,
                    algodUrl = algodUrl,
                    logContext = "$baseContext candidate=$index",
                )
            if (result != null) return result
        }

        Log.d(TAG, "[SESSION_VAULT_REMAINING_MISS] appId=$appId context=$baseContext action=assume_zero_balance")
        return 0L
    }

    fun getRemainingBalanceFromSessionVaultByChannelId(
        channelId: ByteArray,
        appId: Long = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
        algodUrl: String = TESTNET_ALGOD_URL,
        logContext: String? = null,
    ): Long? =
        runCatching {
            val client = algodClient(algodUrl)
            val boxNameB64 = Encoder.encodeToBase64(channelId)
            val response =
                client
                    .GetApplicationBoxByName(appId)
                    .name("b64:$boxNameB64")
                    .execute()

            if (!response.isSuccessful) {
                if (response.code() == 404) {
                    Log.d(
                        TAG,
                        "[SESSION_VAULT_REMAINING_BOX_MISSING] appId=$appId box=b64:$boxNameB64 context=${logContext.orEmpty()}",
                    )
                } else {
                    Log.e(
                        TAG,
                        "[SESSION_VAULT_REMAINING_ERR] reason=box_fetch_failed appId=$appId box=b64:$boxNameB64 context=${logContext.orEmpty()} code=${response.code()} message=${response.message()}",
                    )
                }
                return null
            }

            val sessionBytes = response.body()?.value
            if (sessionBytes == null) {
                Log.e(
                    TAG,
                    "[SESSION_VAULT_REMAINING_ERR] reason=empty_box_value appId=$appId box=b64:$boxNameB64 context=${logContext.orEmpty()}",
                )
                return null
            }

            decodeRemainingBalanceFromSessionInfo(sessionBytes)
        }.onFailure {
            Log.e(
                TAG,
                "[SESSION_VAULT_REMAINING_ERR] reason=exception appId=$appId context=${logContext.orEmpty()} algodUrl=$algodUrl",
                it,
            )
        }.getOrNull()

    suspend fun openSessionAndDeposit(
        signer: MppWalletSigner,
        viewerAddress: String,
        creatorAddress: String,
        depositAmountMicroUsdc: Long = DEPOSIT_MICRO_USDC_LONG,
        appId: Long = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
        usdcAssetId: Long = AssetConstants.USDC_TESTNET_ID,
        algodUrl: String = TESTNET_ALGOD_URL,
    ): Result<String> =
        contractClient(
            appId = appId,
            usdcAssetId = usdcAssetId,
            algodUrl = algodUrl,
        ).openAndDeposit(
            signer = signer,
            payeeAddress = creatorAddress,
            depositMicroUsdc = depositAmountMicroUsdc,
            authorizedSignerAddress = viewerAddress,
            signerType = signer.signerType,
            algodUrl = algodUrl,
        )

    suspend fun settleVoucher(
        signer: MppWalletSigner,
        appId: Long,
        viewerAddress: String,
        hostAddress: String,
        totalAmountUsedMicroUsdc: Long,
        signature: ByteArray,
        algodUrl: String = TESTNET_ALGOD_URL,
    ): Result<String> {
        val channelId = deriveChannelId(viewerAddress, hostAddress, viewerAddress)
        return contractClient(
            appId = appId,
            usdcAssetId = AssetConstants.USDC_TESTNET_ID,
            algodUrl = algodUrl,
        ).settle(
            signer = signer,
            channelId = channelId,
            cumulativeAmountMicroUsdc = totalAmountUsedMicroUsdc,
            signature = signature,
            algodUrl = algodUrl,
        )
    }

    private fun encodeUint64(value: Long): ByteArray =
        ByteBuffer
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
        // ChannelInfo layout in EscrowSessionVaultManager:
        // payer(32) + payee(32) + authorizedSigner(32) + signerType(8)
        // + totalDeposit(8) + lastSettled(8) + latestVoucherAmount(8)
        // + startRound(8) + startTimestamp(8) + closeRequestedAt(8)
        if (bytes.size < 120) error("Invalid session box payload size=${bytes.size}")
        val totalDeposit = decodeUint64BigEndian(bytes, 104)
        val lastSettled = decodeUint64BigEndian(bytes, 112)
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

    fun deriveChannelId(
        viewerAddress: String,
        hostAddress: String,
        authorizedSignerAddress: String = viewerAddress,
        usdcAssetId: Long = AssetConstants.USDC_TESTNET_ID,
        salt: ByteArray = CHANNEL_ID_SALT,
    ): ByteArray {
        ensureBouncyCastleProvider()
        return contractClient(usdcAssetId = usdcAssetId).deriveChannelId(
            payerAddress = viewerAddress,
            payeeAddress = hostAddress,
            authorizedSignerAddress = authorizedSignerAddress,
            salt = salt,
        )
    }

    fun buildClaimMessage(
        appId: Long,
        channelId: ByteArray,
        totalAmountClaimedMicroUsdc: Long,
    ): ByteArray {
        val appBytes = ByteBuffer.allocate(8).putLong(appId).array()
        val amountBytes = ByteBuffer.allocate(8).putLong(totalAmountClaimedMicroUsdc).array()
        return appBytes + channelId + amountBytes + "settle".toByteArray(StandardCharsets.UTF_8)
    }

    fun buildClaimMessage(
        appId: Long,
        viewerAddress: String,
        hostAddress: String,
        totalAmountClaimedMicroUsdc: Long,
    ): ByteArray =
        buildClaimMessage(
            appId = appId,
            channelId = deriveChannelId(viewerAddress, hostAddress, viewerAddress),
            totalAmountClaimedMicroUsdc = totalAmountClaimedMicroUsdc,
        )

    fun serializeVoucherSignature(signature: ByteArray): String =
        java.util.Base64
            .getEncoder()
            .encodeToString(signature)

    fun buildClaimMessageHashHex(
        appId: Long,
        channelId: ByteArray,
        totalAmountClaimedMicroUsdc: Long,
    ): String {
        val message = buildClaimMessage(appId, channelId, totalAmountClaimedMicroUsdc)
        val digest = MessageDigest.getInstance("SHA-256").digest(message)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun buildClaimMessageHashHex(
        appId: Long,
        viewerAddress: String,
        hostAddress: String,
        totalAmountClaimedMicroUsdc: Long,
    ): String =
        buildClaimMessageHashHex(
            appId = appId,
            channelId = deriveChannelId(viewerAddress, hostAddress, viewerAddress),
            totalAmountClaimedMicroUsdc = totalAmountClaimedMicroUsdc,
        )

    fun signClaimMessageWithAlgoSdk(
        secretKey: ByteArray,
        appId: Long,
        channelId: ByteArray,
        totalAmountClaimedMicroUsdc: Long,
    ): ByteArray? =
        runCatching {
            val message = buildClaimMessage(appId, channelId, totalAmountClaimedMicroUsdc)
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
        channelId: ByteArray,
        totalAmountClaimedMicroUsdc: Long,
        signature: ByteArray,
    ): Boolean =
        runCatching {
            val message = buildClaimMessage(appId, channelId, totalAmountClaimedMicroUsdc)
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
        hostAddress: String,
        totalAmountClaimedMicroUsdc: Long,
        signature: ByteArray,
        algodUrl: String = TESTNET_ALGOD_URL,
    ): Result<VerifyClaimVoucherOnChainResult> =
        runCatching {
            val channelId = deriveChannelId(viewerAddress, hostAddress, viewerAddress)
            val client = algodClient(algodUrl)
            val params = client.TransactionParams().execute().body()
            params.fee = VERIFY_HELPER_APP_CALL_FEE

            val encodedSig = encodeArc4DynamicBytes(signature)
            val encodedChannelId = encodeArc4DynamicBytes(channelId)
            val selectorHex = ABI_VERIFY_SETTLE_SIGNATURE.joinToString("") { "%02x".format(it) }
            val localVerify =
                verifyClaimSignatureLocally(
                    viewerAddress = viewerAddress,
                    appId = appId,
                    channelId = channelId,
                    totalAmountClaimedMicroUsdc = totalAmountClaimedMicroUsdc,
                    signature = signature,
                )
            Log.e(
                TAG,
                "[VERIFY_HELPER_BUILD] appId=$appId sender=${signer.address} viewer=$viewerAddress host=$hostAddress amount=$totalAmountClaimedMicroUsdc selector=$selectorHex sigLen=${signature.size} arc4SigLen=${encodedSig.size} channelLen=${encodedChannelId.size} localVerify=$localVerify",
            )

            val appCallTxn =
                Transaction
                    .ApplicationCallTransactionBuilder()
                    .sender(signer.address)
                    .suggestedParams(params)
                    .applicationId(appId)
                    .args(
                        listOf(
                            ABI_VERIFY_SETTLE_SIGNATURE,
                            encodedChannelId,
                            encodeUint64(totalAmountClaimedMicroUsdc),
                            encodedSig,
                        ),
                    ).boxReferences(listOf(AppBoxReference(appId, channelId)))
                    .build()

            appCallTxn.fee = BigInteger.valueOf(VERIFY_HELPER_APP_CALL_FEE)
            val signedAppCall = signer.signTransaction(appCallTxn)
            Log.e(
                TAG,
                "[VERIFY_HELPER_BROADCAST_ATTEMPT] appId=$appId sender=${signer.address} viewer=$viewerAddress host=$hostAddress amount=$totalAmountClaimedMicroUsdc txId=${appCallTxn.txID()}",
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
                "[VERIFY_HELPER_BROADCAST_ERR] appId=$appId sender=${signer.address} viewer=$viewerAddress host=$hostAddress amount=$totalAmountClaimedMicroUsdc",
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

    @Synchronized
    private fun ensureBouncyCastleProvider() {
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 0)
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
