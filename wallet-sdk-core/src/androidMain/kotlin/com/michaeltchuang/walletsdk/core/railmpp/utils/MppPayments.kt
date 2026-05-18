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
import com.michaeltchuang.walletsdk.core.railmpp.internal.BouncyCastleProviderSetup
import com.michaeltchuang.walletsdk.core.railmpp.smartcontract.EscrowSessionVaultManagerClient
import com.michaeltchuang.walletsdk.core.utils.LiquidStreamConstants
import org.bouncycastle.crypto.digests.SHA512tDigest
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * MPP payment helper for Liquid Stream.
 *
 * Note: current implementation supports Algorand and Solana transfer construction/signing.
 */
object MppPayments {
    private const val TAG = "MppPayments"

    fun contractClient(
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
        BouncyCastleProviderSetup.ensure()
    }

    private const val DEPOSIT_MICRO_USDC_LONG = LiquidStreamConstants.DEPOSIT_AMOUNT_MICRO_USDC
    private const val COST_PER_BLOCK_MICRO_USDC = LiquidStreamConstants.COST_PER_BLOCK_MICRO_USDC
    private const val VOUCHER_SETTLE_EVERY_BLOCKS = 1
    private const val VERIFY_HELPER_APP_CALL_FEE = 12000L
    private val CHANNEL_ID_SALT = "walletsdk-session-v1".toByteArray(StandardCharsets.UTF_8)
    private val ABI_VERIFY_SETTLE_SIGNATURE = byteArrayOf(0x27, 0x04, 0x92.toByte(), 0x89.toByte())
    private val AUTHORIZED_SIGNER_PUBLIC_KEY_BOX_PREFIX = "p".toByteArray(StandardCharsets.UTF_8)
    const val TESTNET_ALGOD_URL = "https://testnet-api.algonode.cloud"
    private const val ALGORAND_ADDRESS_PUBLIC_KEY_LENGTH = 32
    private const val ALGORAND_ADDRESS_CHECKSUM_LENGTH = 4

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
                "costPerBlockMicroUsdc":10000
            }
            """.trimIndent()
    }

    fun createVoucherJson(
        sessionId: String,
        appId: Long = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
        viewerAddress: String,
        viewerPublicKey: ByteArray,
        creatorAddress: String,
        blocksConsumed: Int,
        totalAmountUsed: Long,
        remainingMicroUsdc: Long,
        signatureBase64: String? = null,
    ): String {
        val voucherPayload =
            JSONObject().apply {
                put("reference", "liquid:payment:voucher")
                put("id", sessionId)
                put("appId", appId)
                put("viewer", viewerAddress)
                put(
                    "viewerPublicKey",
                    java.util.Base64
                        .getEncoder()
                        .encodeToString(viewerPublicKey),
                )
                put("creator", creatorAddress)
                put("blocksWatched", blocksConsumed)
                put("costPerBlockMicroUsdc", COST_PER_BLOCK_MICRO_USDC)
                put("totalAmountClaimedMicroUsdc", totalAmountUsed)
                put("remainingMicroUsdc", remainingMicroUsdc)
                signatureBase64?.takeIf { it.isNotBlank() }?.let { put("signature", it) }
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
        appId: Long,
        algodUrl: String?,
        authorizedSignerPublicKey: ByteArray?,
    ): Long {
        BouncyCastleProviderSetup.ensure()
        val baseContext = "viewer=$viewerAddress host=$hostAddress"
        val channelId =
            deriveChannelId(
                viewerAddress = viewerAddress,
                hostAddress = hostAddress,
                authorizedSignerPublicKey = authorizedSignerPublicKey ?: Address(viewerAddress).getBytes(),
            )
        val result =
            getRemainingBalanceByChannelId(
                channelId = channelId,
                appId = appId,
                algodUrl = algodUrl,
                logContext = baseContext,
            )
        Log.e(
            TAG,
            "[SESSION_VAULT_REMAINING_BALANCE_CHECK] GetRemainingBalance=${result ?: "null"}",
        )
        if (result != null) return result

        Log.d(
            TAG,
            "[SESSION_VAULT_REMAINING_MISS] appId=$appId context=$baseContext action=assume_zero_balance",
        )
        return 0L
    }

    private fun getRemainingBalanceByChannelId(
        channelId: ByteArray,
        appId: Long,
        algodUrl: String?,
        logContext: String,
    ): Long? =
        if (algodUrl == null) {
            getRemainingBalanceFromSessionVaultByChannelId(
                channelId = channelId,
                appId = appId,
                logContext = logContext,
            )
        } else {
            getRemainingBalanceFromSessionVaultByChannelId(
                channelId = channelId,
                appId = appId,
                algodUrl = algodUrl,
                logContext = logContext,
            )
        }

    private fun decodeAlgorandAddressPublicKey(address: String): ByteArray {
        val decoded = decodeBase32(address)
        require(decoded.size >= ALGORAND_ADDRESS_PUBLIC_KEY_LENGTH + ALGORAND_ADDRESS_CHECKSUM_LENGTH) {
            "Invalid Algorand address length"
        }
        return decoded.copyOfRange(0, ALGORAND_ADDRESS_PUBLIC_KEY_LENGTH)
    }

    private fun decodeBase32(value: String): ByteArray {
        var buffer = 0
        var bitsLeft = 0
        val bytes = mutableListOf<Byte>()

        value
            .trim()
            .trimEnd('=')
            .uppercase()
            .forEach { char ->
                val charValue =
                    when (char) {
                        in 'A'..'Z' -> char - 'A'
                        in '2'..'7' -> char - '2' + 26
                        else -> error("Invalid base32 character: $char")
                    }

                buffer = (buffer shl 5) or charValue
                bitsLeft += 5

                if (bitsLeft >= 8) {
                    bitsLeft -= 8
                    bytes.add(((buffer shr bitsLeft) and 0xFF).toByte())
                }
            }

        return bytes.toByteArray()
    }

    private fun sha512256(bytes: ByteArray): ByteArray {
        val digest = SHA512tDigest(256)
        val output = ByteArray(32)
        digest.update(bytes, 0, bytes.size)
        digest.doFinal(output, 0)
        return output
    }

    fun getRemainingBalanceFromSessionVaultByChannelId(
        channelId: ByteArray,
        appId: Long = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
        algodUrl: String = TESTNET_ALGOD_URL,
        logContext: String? = null,
    ): Long? =
        runCatching {
            val dynamicData =
                contractClient(
                    appId = appId,
                    usdcAssetId = AssetConstants.USDC_TESTNET_ID,
                    algodUrl = algodUrl,
                ).getSessionDynamicData(
                    channelId = channelId,
                    algodUrl = algodUrl,
                ).getOrThrow()

            (dynamicData.totalDeposit - dynamicData.lastSettled).coerceAtLeast(0L)
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
    ): Result<String> {
        require(viewerAddress.isNotBlank()) { "viewerAddress is required" }
        return contractClient(
            appId = appId,
            usdcAssetId = usdcAssetId,
            algodUrl = algodUrl,
        ).openAndDeposit(
            signer = signer,
            payeeAddress = creatorAddress,
            depositMicroUsdc = depositAmountMicroUsdc,
            authorizedSignerPublicKey = signer.authorizedSignerPublicKey,
            signerType = signer.signerType,
            algodUrl = algodUrl,
        )
    }

    suspend fun topUpSessionVault(
        signer: MppWalletSigner,
        viewerAddress: String,
        hostAddress: String,
        additionalDepositMicroUsdc: Long,
        appId: Long = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
        usdcAssetId: Long = AssetConstants.USDC_TESTNET_ID,
        authorizedSignerPublicKey: ByteArray = signer.authorizedSignerPublicKey,
        algodUrl: String = TESTNET_ALGOD_URL,
    ): Result<String> {
        val channelId =
            deriveChannelId(signer.address, hostAddress, authorizedSignerPublicKey, usdcAssetId)
        return contractClient(
            appId = appId,
            usdcAssetId = usdcAssetId,
            algodUrl = algodUrl,
        ).topUp(
            signer = signer,
            channelId = channelId,
            additionalDepositMicroUsdc = additionalDepositMicroUsdc,
            algodUrl = algodUrl,
        )
    }

    suspend fun setAuthorizedSignerForSession(
        signer: MppWalletSigner,
        appId: Long,
        viewerAddress: String,
        hostAddress: String,
        authorizedSignerPublicKey: ByteArray = signer.authorizedSignerPublicKey,
        algodUrl: String = TESTNET_ALGOD_URL,
    ): Result<String> {
        val channelId = deriveChannelId(viewerAddress, hostAddress, authorizedSignerPublicKey)
        val result =
            contractClient(
                appId = appId,
                usdcAssetId = AssetConstants.USDC_TESTNET_ID,
                algodUrl = algodUrl,
            ).setAuthorizedSignerPublicKey(
                signer = signer,
                channelId = channelId,
                authorizedSignerPublicKey = authorizedSignerPublicKey,
                algodUrl = algodUrl,
            )

        result
            .onSuccess { txId ->
                Log.d(
                    TAG,
                    "[VIEWER_SET_AUTH_SIGNER_OK] appId=$appId signer=${signer.address} viewer=$viewerAddress host=$hostAddress txId=$txId",
                )
            }.onFailure { throwable ->
                Log.e(
                    TAG,
                    "[VIEWER_SET_AUTH_SIGNER_ERR] appId=$appId signer=${signer.address} viewer=$viewerAddress host=$hostAddress",
                    throwable,
                )
            }

        return result
    }

    suspend fun updateVoucherOnChain(
        signer: MppWalletSigner,
        appId: Long,
        viewerAddress: String,
        hostAddress: String,
        totalAmountUsedMicroUsdc: Long,
        signature: ByteArray,
        authorizedSignerPublicKey: ByteArray = signer.authorizedSignerPublicKey,
        algodUrl: String = TESTNET_ALGOD_URL,
    ): Result<String> {
        val channelId = deriveChannelId(viewerAddress, hostAddress, authorizedSignerPublicKey)
        val channelIdHash = hashHex(channelId).take(16)
        Log.d(
            TAG,
            "[VIEWER_UPDATE_VOUCHER_ATTEMPT] appId=$appId signer=${signer.address} viewer=$viewerAddress host=$hostAddress claimedMicroUsdc=$totalAmountUsedMicroUsdc sigLen=${signature.size} signerPkLen=${authorizedSignerPublicKey.size} channelIdHash=$channelIdHash",
        )

        val result =
            contractClient(
                appId = appId,
                usdcAssetId = AssetConstants.USDC_TESTNET_ID,
                algodUrl = algodUrl,
            ).updateVoucher(
                signer = signer,
                channelId = channelId,
                cumulativeAmountMicroUsdc = totalAmountUsedMicroUsdc,
                signature = signature,
                algodUrl = algodUrl,
            )

        result
            .onSuccess { txId ->
                Log.d(
                    TAG,
                    "[VIEWER_UPDATE_VOUCHER_OK] txId=$txId signer=${signer.address} viewer=$viewerAddress host=$hostAddress claimedMicroUsdc=$totalAmountUsedMicroUsdc channelIdHash=$channelIdHash",
                )
            }.onFailure { throwable ->
                val errText = throwable.message.orEmpty()
                val duplicateVoucherUpdate =
                    errText.contains("pc=622", ignoreCase = true) &&
                        (
                            errText.contains("opcodes=dig 2", ignoreCase = true) ||
                                errText.contains(
                                    "Voucher not increasing",
                                    ignoreCase = true,
                                )
                        )
                if (duplicateVoucherUpdate) {
                    Log.e(
                        TAG,
                        "[VIEWER_UPDATE_VOUCHER_DUPLICATE_SKIP] signer=${signer.address} viewer=$viewerAddress host=$hostAddress claimedMicroUsdc=$totalAmountUsedMicroUsdc channelIdHash=$channelIdHash reason=already_recorded_onchain",
                    )
                } else {
                    Log.e(
                        TAG,
                        "[VIEWER_UPDATE_VOUCHER_ERR] signer=${signer.address} viewer=$viewerAddress host=$hostAddress claimedMicroUsdc=$totalAmountUsedMicroUsdc sigLen=${signature.size} signerPkLen=${authorizedSignerPublicKey.size} channelIdHash=$channelIdHash",
                    )
                }
            }

        return result
    }

    suspend fun settleLatestVoucher(
        signer: MppWalletSigner,
        appId: Long,
        viewerAddress: String,
        hostAddress: String,
        authorizedSignerPublicKey: ByteArray = Address(viewerAddress).getBytes(),
        algodUrl: String = TESTNET_ALGOD_URL,
    ): Result<String> {
        val channelId = deriveChannelId(viewerAddress, hostAddress, authorizedSignerPublicKey)
        return contractClient(
            appId = appId,
            usdcAssetId = AssetConstants.USDC_TESTNET_ID,
            algodUrl = algodUrl,
        ).settleLatest(
            signer = signer,
            channelId = channelId,
            algodUrl = algodUrl,
        )
    }

    suspend fun settle(
        signer: MppWalletSigner,
        viewerAddress: String,
        hostAddress: String,
        appId: Long,
        algodUrl: String = TESTNET_ALGOD_URL,
        cumulativeAmountMicroUsdc: Long,
        signature: ByteArray,
        // The authorized signer public key used when the channel was opened (payer/viewer key).
        // Used to derive the channelId and must match what was stored on-chain.
        // Defaults to signer.authorizedSignerPublicKey for backward compatibility, but callers
        // should explicitly pass the viewer/payer's key when signer is the payee (creator).
        authorizedSignerPublicKey: ByteArray = signer.authorizedSignerPublicKey,
    ): Result<String> {
        val channelId =
            contractClient(appId = appId).deriveChannelId(
                payerAddress = viewerAddress,
                payeeAddress = hostAddress,
                authorizedSignerPublicKey = authorizedSignerPublicKey,
            )
        return contractClient(
            appId = appId,
            usdcAssetId = AssetConstants.USDC_TESTNET_ID,
            algodUrl = algodUrl,
        ).settle(
            signer = signer,
            channelId = channelId,
            cumulativeAmountMicroUsdc = cumulativeAmountMicroUsdc,
            signature = signature,
            algodUrl = algodUrl,
        )
    }

    suspend fun verifySettleSignature(
        signer: MppWalletSigner,
        viewerAddress: String,
        hostAddress: String,
        cumulativeAmountMicroUsdc: Long,
        signature: ByteArray,
        algodUrl: String = TESTNET_ALGOD_URL,
    ): Result<String> {
        val channelId =
            contractClient().deriveChannelId(
                payerAddress = viewerAddress,
                payeeAddress = hostAddress,
                authorizedSignerPublicKey = signer.authorizedSignerPublicKey,
            )
        return contractClient(
            appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
            usdcAssetId = AssetConstants.USDC_TESTNET_ID,
            algodUrl = algodUrl,
        ).verifySettleSignature(
            signer = signer,
            channelId = channelId,
            cumulativeAmountMicroUsdc = cumulativeAmountMicroUsdc,
            signature = signature,
            algodUrl = algodUrl,
        )
    }

    fun settleMessage(
        signer: MppWalletSigner,
        viewerAddress: String,
        hostAddress: String,
        cumulativeAmountMicroUsdc: Long,
    ): ByteArray {
        val channelId =
            contractClient().deriveChannelId(
                payerAddress = viewerAddress,
                payeeAddress = hostAddress,
                authorizedSignerPublicKey = signer.authorizedSignerPublicKey,
            )
        return contractClient().settleMessage(
            channelId = channelId,
            cumulativeAmountMicroUsdc = cumulativeAmountMicroUsdc,
        )
    }

    data class SessionDynamicData(
        val totalDeposit: Long,
        val lastSettled: Long,
        val latestVoucherAmount: Long,
    ) {
        val unclaimedVoucherAmount: Long
            get() =
                (latestVoucherAmount - lastSettled).coerceAtLeast(
                    0L,
                )
    }

    data class SessionProgressSnapshot(
        val totalDepositMicroUsdc: Long,
        val remainingSettledMicroUsdc: Long,
        val progressBalanceMicroUsdc: Long,
        val lastSettledMicroUsdc: Long,
        val latestVoucherAmountMicroUsdc: Long,
    )

    fun computeSessionProgressSnapshot(dynamicData: SessionDynamicData): SessionProgressSnapshot {
        val totalDeposit = dynamicData.totalDeposit.coerceAtLeast(0L)
        val lastSettled = dynamicData.lastSettled.coerceAtLeast(0L)
        val latestVoucherAmount = dynamicData.latestVoucherAmount.coerceAtLeast(0L)
        val remainingSettled = (totalDeposit - lastSettled).coerceAtLeast(0L)
        val effectiveClaimed = maxOf(lastSettled, latestVoucherAmount)
        val progressBalance = (totalDeposit - effectiveClaimed).coerceAtLeast(0L)

        return SessionProgressSnapshot(
            totalDepositMicroUsdc = totalDeposit,
            remainingSettledMicroUsdc = remainingSettled,
            progressBalanceMicroUsdc = progressBalance,
            lastSettledMicroUsdc = lastSettled,
            latestVoucherAmountMicroUsdc = latestVoucherAmount,
        )
    }

    fun getSessionProgressSnapshotFromVault(
        viewerAddress: String,
        hostAddress: String,
        appId: Long = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
        algodUrl: String = TESTNET_ALGOD_URL,
        authorizedSignerPublicKey: ByteArray? = null,
    ): SessionProgressSnapshot? {
        val dynamicData =
            getSessionDynamicDataFromVault(
                viewerAddress = viewerAddress,
                hostAddress = hostAddress,
                appId = appId,
                algodUrl = algodUrl,
                authorizedSignerPublicKey = authorizedSignerPublicKey,
            ) ?: return null

        return computeSessionProgressSnapshot(dynamicData)
    }

    fun getSessionDynamicDataFromVault(
        viewerAddress: String,
        hostAddress: String,
        appId: Long = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
        algodUrl: String = TESTNET_ALGOD_URL,
        authorizedSignerPublicKey: ByteArray? = null,
    ): SessionDynamicData? {
        BouncyCastleProviderSetup.ensure()
        val baseContext = "viewer=$viewerAddress host=$hostAddress"
        val viewerPublicKey = decodeAlgorandAddressPublicKey(viewerAddress)
        val hostPublicKey = decodeAlgorandAddressPublicKey(hostAddress)
        val signerCandidates =
            authorizedSignerPublicKey
                ?.takeIf { it.isNotEmpty() }
                ?.let { listOf(it) }
                ?: listOf(viewerPublicKey, hostPublicKey)
        val channelIdCandidates =
            signerCandidates
                .map { signerKey ->
                    deriveChannelId(
                        viewerAddress,
                        hostAddress,
                        authorizedSignerPublicKey = signerKey,
                        salt = CHANNEL_ID_SALT,
                    )
                }.distinctBy { Encoder.encodeToBase64(it) }

        channelIdCandidates.forEachIndexed { index, channelId ->
            val result =
                getSessionDynamicDataFromVaultByChannelId(
                    channelId = channelId,
                    appId = appId,
                    algodUrl = algodUrl,
                    logContext = "$baseContext candidate=$index",
                )
            if (result != null) return result
        }

        return null
    }

    fun getSessionDynamicDataFromVaultByChannelId(
        channelId: ByteArray,
        appId: Long = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
        algodUrl: String = TESTNET_ALGOD_URL,
        logContext: String? = null,
    ): SessionDynamicData? =
        runCatching {
            val dynamicData =
                contractClient(
                    appId = appId,
                    usdcAssetId = AssetConstants.USDC_TESTNET_ID,
                    algodUrl = algodUrl,
                ).getSessionDynamicData(
                    channelId = channelId,
                    algodUrl = algodUrl,
                ).getOrThrow()

            SessionDynamicData(
                totalDeposit = dynamicData.totalDeposit,
                lastSettled = dynamicData.lastSettled,
                latestVoucherAmount = dynamicData.latestVoucherAmount,
            )
        }.onFailure {
            Log.e(
                TAG,
                "[SESSION_VAULT_DYNAMIC_ERR] reason=exception appId=$appId context=${logContext.orEmpty()} algodUrl=$algodUrl",
                it,
            )
        }.getOrNull()

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
        val resp: Response<PostTransactionsResponse> =
            client.RawTransaction().rawtxn(concatenated).execute()
        if (!resp.isSuccessful) {
            val err = resp.message() ?: "algod rejected the group"
            error("SessionVault broadcast failed: $err")
        }
        return resp.body()?.txId
    }

    fun deriveChannelId(
        viewerAddress: String,
        hostAddress: String,
        authorizedSignerPublicKey: ByteArray = Address(viewerAddress).getBytes(),
        usdcAssetId: Long = AssetConstants.USDC_TESTNET_ID,
        salt: ByteArray = CHANNEL_ID_SALT,
    ): ByteArray {
        BouncyCastleProviderSetup.ensure()
        return contractClient(usdcAssetId = usdcAssetId).deriveChannelId(
            payerAddress = viewerAddress,
            payeeAddress = hostAddress,
            authorizedSignerPublicKey = authorizedSignerPublicKey,
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
        authorizedSignerPublicKey: ByteArray = Address(viewerAddress).getBytes(),
    ): ByteArray =
        buildClaimMessage(
            appId = appId,
            channelId = deriveChannelId(viewerAddress, hostAddress, authorizedSignerPublicKey),
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
            channelId =
                deriveChannelId(
                    viewerAddress,
                    hostAddress,
                    Address(viewerAddress).getBytes(),
                ),
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
        authorizedSignerPublicKey: ByteArray = Address(viewerAddress).getBytes(),
        algodUrl: String = TESTNET_ALGOD_URL,
    ): Result<VerifyClaimVoucherOnChainResult> =
        runCatching {
            val channelId = deriveChannelId(viewerAddress, hostAddress, authorizedSignerPublicKey)
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
                    ).boxReferences(
                        listOf(
                            AppBoxReference(appId, channelId),
                            AppBoxReference(
                                appId,
                                AUTHORIZED_SIGNER_PUBLIC_KEY_BOX_PREFIX + channelId,
                            ),
                        ),
                    ).build()

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

    /**
     * Polls the Algorand node until [txId] reaches a confirmed round or [maxRounds] is exceeded.
     *
     * **Must be called on an IO dispatcher** — it blocks with [Thread.sleep] between polls.
     *
     * @return `true` if the transaction was confirmed, `false` if the polling window expired.
     */
    fun awaitTransactionConfirmation(
        txId: String,
        algodUrl: String = TESTNET_ALGOD_URL,
        maxRounds: Int = 10,
    ): Boolean {
        val client = algodClient(algodUrl)
        return waitForPendingTransaction(client, txId, maxRounds)
            ?.confirmedRound
            ?.let { it > 0L }
            ?: false
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
