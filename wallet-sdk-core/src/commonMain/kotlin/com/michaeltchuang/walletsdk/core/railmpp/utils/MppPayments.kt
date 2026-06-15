package com.michaeltchuang.walletsdk.core.railmpp.utils

import com.michaeltchuang.walletsdk.core.deeplink.utils.AssetConstants
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.internal.awaitConfirmationDetailsInternal
import com.michaeltchuang.walletsdk.core.railmpp.internal.awaitConfirmationInternal
import com.michaeltchuang.walletsdk.core.railmpp.internal.decodeAlgorandAddressPublicKey
import com.michaeltchuang.walletsdk.core.railmpp.internal.decodeMsgPackAny
import com.michaeltchuang.walletsdk.core.railmpp.internal.encodeUint64
import com.michaeltchuang.walletsdk.core.railmpp.internal.sha256
import com.michaeltchuang.walletsdk.core.railmpp.internal.signEd25519
import com.michaeltchuang.walletsdk.core.railmpp.internal.verifyEd25519
import com.michaeltchuang.walletsdk.core.railmpp.smartcontract.EscrowSessionVaultManagerClient
import com.michaeltchuang.walletsdk.core.utils.LiquidStreamConstants
import io.github.aakira.napier.Napier
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Top-level helper for Liquid Stream MPP payment operations. */
object MppPayments {
    private const val TAG = "MppPayments"

    const val TESTNET_ALGOD_URL = "https://testnet-api.algonode.cloud"

    private const val DEPOSIT_MICRO_USDC_LONG = LiquidStreamConstants.DEPOSIT_AMOUNT_MICRO_USDC
    private const val COST_PER_BLOCK_MICRO_USDC = LiquidStreamConstants.COST_PER_BLOCK_MICRO_USDC
    private const val VOUCHER_SETTLE_EVERY_BLOCKS = 1
    private val CHANNEL_ID_SALT = "walletsdk-session-v1".encodeToByteArray()
    private val AUTHORIZED_SIGNER_PUBLIC_KEY_BOX_PREFIX = "p".encodeToByteArray()

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

    fun decodeSignedTransaction(bytes: ByteArray): Any? =
        runCatching { decodeMsgPackAny(bytes) }
            .onFailure {
                Napier.e("Failed to decode signed transaction", it, tag = TAG)
            }.getOrNull()

    @OptIn(ExperimentalEncodingApi::class)
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
        val viewerPkBase64 = Base64.encode(viewerPublicKey)

        return buildJsonObject {
            put("reference", "liquid:payment:voucher")
            put("id", sessionId)
            put("appId", appId)
            put("viewer", viewerAddress)
            put("viewerPublicKey", viewerPkBase64)
            put("creator", creatorAddress)
            put("blocksWatched", blocksConsumed)
            put("costPerBlockMicroUsdc", COST_PER_BLOCK_MICRO_USDC)
            put("totalAmountClaimedMicroUsdc", totalAmountUsed)
            put("remainingMicroUsdc", remainingMicroUsdc)

            signatureBase64?.takeIf { it.isNotBlank() }?.let {
                put("signature", it)
            }
        }.toString()
    }

    fun shouldAttemptVoucherSettlement(blocksConsumed: Int): Boolean =
        blocksConsumed > 0 && blocksConsumed % VOUCHER_SETTLE_EVERY_BLOCKS == 0

    fun computeVoucherMicroUsdcUsage(blocksConsumed: Int): Long = (blocksConsumed.toLong() * COST_PER_BLOCK_MICRO_USDC).coerceAtLeast(0L)

    fun voucherSettleWindowMicroUsdc(): Long = (VOUCHER_SETTLE_EVERY_BLOCKS.toLong() * COST_PER_BLOCK_MICRO_USDC).coerceAtLeast(0L)

    fun estimateRemainingUsdcBalanceFromBlocks(blocksConsumed: Int): Long =
        (DEPOSIT_MICRO_USDC_LONG - blocksConsumed.toLong() * COST_PER_BLOCK_MICRO_USDC).coerceAtLeast(0L)

    fun remainingUsdcFromMicroAlgos(remainingMicroAlgos: Long): Double = remainingMicroAlgos / 1_000_000.0

    fun maxSessionDepositMicroUsdc(): Long = DEPOSIT_MICRO_USDC_LONG

    fun getRemainingBalanceFromSessionVault(
        viewerAddress: String,
        hostAddress: String,
        appId: Long,
        algodUrl: String?,
        authorizedSignerPublicKey: ByteArray?,
    ): Long {
        val baseContext = "viewer=$viewerAddress host=$hostAddress"
        val channelId =
            deriveChannelId(
                viewerAddress = viewerAddress,
                hostAddress = hostAddress,
                authorizedSignerPublicKey = authorizedSignerPublicKey ?: decodeAlgorandAddressPublicKey(viewerAddress),
            )
        val result = getRemainingBalanceByChannelId(channelId, appId, algodUrl, baseContext)
        Napier.e("[SESSION_VAULT_REMAINING_BALANCE_CHECK] result=${result ?: "null"}", tag = TAG)
        if (result != null) return result
        Napier.d("[SESSION_VAULT_REMAINING_MISS] appId=$appId $baseContext", tag = TAG)
        return 0L
    }

    private fun getRemainingBalanceByChannelId(
        channelId: ByteArray,
        appId: Long,
        algodUrl: String?,
        logContext: String,
    ): Long? =
        if (algodUrl == null) {
            getRemainingBalanceFromSessionVaultByChannelId(channelId, appId, logContext = logContext)
        } else {
            getRemainingBalanceFromSessionVaultByChannelId(channelId, appId, algodUrl, logContext)
        }

    fun getRemainingBalanceFromSessionVaultByChannelId(
        channelId: ByteArray,
        appId: Long = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
        algodUrl: String = TESTNET_ALGOD_URL,
        logContext: String? = null,
    ): Long? =
        runCatching {
            val data =
                contractClient(appId = appId, algodUrl = algodUrl)
                    .getSessionDynamicData(channelId, algodUrl)
                    .getOrThrow()
            (data.totalDeposit - data.lastSettled).coerceAtLeast(0L)
        }.onFailure {
            Napier.e("[SESSION_VAULT_REMAINING_ERR] appId=$appId context=${logContext.orEmpty()}", it, tag = TAG)
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
        return contractClient(appId, usdcAssetId, algodUrl).openAndDeposit(
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
        val channelId = deriveChannelId(signer.address, hostAddress, authorizedSignerPublicKey, usdcAssetId)
        return contractClient(appId, usdcAssetId, algodUrl).topUp(signer, channelId, additionalDepositMicroUsdc, algodUrl)
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
            contractClient(appId, algodUrl = algodUrl).setAuthorizedSignerPublicKey(
                signer,
                channelId,
                authorizedSignerPublicKey,
                algodUrl,
            )
        result
            .onSuccess { Napier.d("[VIEWER_SET_AUTH_SIGNER_OK] appId=$appId txId=$it", tag = TAG) }
            .onFailure { Napier.e("[VIEWER_SET_AUTH_SIGNER_ERR] appId=$appId viewer=$viewerAddress", it, tag = TAG) }
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
        Napier.d(
            "[VIEWER_UPDATE_VOUCHER_ATTEMPT] appId=$appId viewer=$viewerAddress host=$hostAddress claimedMicroUsdc=$totalAmountUsedMicroUsdc channelIdHash=$channelIdHash",
            tag = TAG,
        )

        val result =
            contractClient(appId, algodUrl = algodUrl).updateVoucher(
                signer,
                channelId,
                totalAmountUsedMicroUsdc,
                signature,
                algodUrl,
            )
        result
            .onSuccess { Napier.d("[VIEWER_UPDATE_VOUCHER_OK] txId=$it channelIdHash=$channelIdHash", tag = TAG) }
            .onFailure { throwable ->
                val err = throwable.message.orEmpty()
                val isDuplicate =
                    err.contains("pc=622", ignoreCase = true) &&
                        (err.contains("opcodes=dig 2", ignoreCase = true) || err.contains("Voucher not increasing", ignoreCase = true))
                if (isDuplicate) {
                    Napier.e(
                        "[VIEWER_UPDATE_VOUCHER_DUPLICATE_SKIP] channelIdHash=$channelIdHash reason=already_recorded_onchain",
                        tag = TAG,
                    )
                } else {
                    Napier.e("[VIEWER_UPDATE_VOUCHER_ERR] channelIdHash=$channelIdHash", throwable, tag = TAG)
                }
            }
        return result
    }

    suspend fun settleLatestVoucher(
        signer: MppWalletSigner,
        appId: Long,
        viewerAddress: String,
        hostAddress: String,
        authorizedSignerPublicKey: ByteArray = decodeAlgorandAddressPublicKey(viewerAddress),
        algodUrl: String = TESTNET_ALGOD_URL,
    ): Result<String> {
        val channelId = deriveChannelId(viewerAddress, hostAddress, authorizedSignerPublicKey)
        return contractClient(appId, algodUrl = algodUrl).settleLatest(signer, channelId, algodUrl)
    }

    suspend fun settle(
        signer: MppWalletSigner,
        viewerAddress: String,
        hostAddress: String,
        appId: Long,
        algodUrl: String = TESTNET_ALGOD_URL,
        cumulativeAmountMicroUsdc: Long,
        signature: ByteArray,
        authorizedSignerPublicKey: ByteArray = signer.authorizedSignerPublicKey,
    ): Result<String> {
        val channelId = contractClient(appId).deriveChannelId(viewerAddress, hostAddress, authorizedSignerPublicKey)
        return contractClient(appId, algodUrl = algodUrl).settle(signer, channelId, cumulativeAmountMicroUsdc, signature, algodUrl)
    }

    suspend fun verifySettleSignature(
        signer: MppWalletSigner,
        viewerAddress: String,
        hostAddress: String,
        cumulativeAmountMicroUsdc: Long,
        signature: ByteArray,
        algodUrl: String = TESTNET_ALGOD_URL,
    ): Result<String> {
        val channelId = contractClient().deriveChannelId(viewerAddress, hostAddress, signer.authorizedSignerPublicKey)
        return contractClient(algodUrl = algodUrl).verifySettleSignature(signer, channelId, cumulativeAmountMicroUsdc, signature, algodUrl)
    }

    fun settleMessage(
        signer: MppWalletSigner,
        viewerAddress: String,
        hostAddress: String,
        cumulativeAmountMicroUsdc: Long,
    ): ByteArray {
        val channelId = contractClient().deriveChannelId(viewerAddress, hostAddress, signer.authorizedSignerPublicKey)
        return contractClient().settleMessage(channelId, cumulativeAmountMicroUsdc)
    }

    data class SessionDynamicData(
        val totalDeposit: Long,
        val lastSettled: Long,
        val latestVoucherAmount: Long,
    ) {
        val unclaimedVoucherAmount: Long get() = (latestVoucherAmount - lastSettled).coerceAtLeast(0L)
    }

    data class SessionProgressSnapshot(
        val totalDepositMicroUsdc: Long,
        val remainingSettledMicroUsdc: Long,
        val progressBalanceMicroUsdc: Long,
        val lastSettledMicroUsdc: Long,
        val latestVoucherAmountMicroUsdc: Long,
    )

    fun computeSessionProgressSnapshot(dynamicData: SessionDynamicData): SessionProgressSnapshot {
        val total = dynamicData.totalDeposit.coerceAtLeast(0L)
        val settled = dynamicData.lastSettled.coerceAtLeast(0L)
        val voucher = dynamicData.latestVoucherAmount.coerceAtLeast(0L)
        return SessionProgressSnapshot(
            totalDepositMicroUsdc = total,
            remainingSettledMicroUsdc = (total - settled).coerceAtLeast(0L),
            progressBalanceMicroUsdc = (total - maxOf(settled, voucher)).coerceAtLeast(0L),
            lastSettledMicroUsdc = settled,
            latestVoucherAmountMicroUsdc = voucher,
        )
    }

    fun getSessionProgressSnapshotFromVault(
        viewerAddress: String,
        hostAddress: String,
        appId: Long = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
        algodUrl: String = TESTNET_ALGOD_URL,
        authorizedSignerPublicKey: ByteArray? = null,
    ): SessionProgressSnapshot? {
        val data =
            getSessionDynamicDataFromVault(viewerAddress, hostAddress, appId, algodUrl, authorizedSignerPublicKey)
                ?: return null
        return computeSessionProgressSnapshot(data)
    }

    fun getSessionDynamicDataFromVault(
        viewerAddress: String,
        hostAddress: String,
        appId: Long = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
        algodUrl: String = TESTNET_ALGOD_URL,
        authorizedSignerPublicKey: ByteArray? = null,
    ): SessionDynamicData? {
        val viewerPk = decodeAlgorandAddressPublicKey(viewerAddress)
        val hostPk = decodeAlgorandAddressPublicKey(hostAddress)
        val signerCandidates =
            authorizedSignerPublicKey?.takeIf { it.isNotEmpty() }?.let { listOf(it) }
                ?: listOf(viewerPk, hostPk)
        val channelIdCandidates =
            signerCandidates
                .map { deriveChannelId(viewerAddress, hostAddress, it, salt = CHANNEL_ID_SALT) }
                .distinctBy { it.toList() }

        channelIdCandidates.forEachIndexed { index, channelId ->
            val result = getSessionDynamicDataFromVaultByChannelId(channelId, appId, algodUrl, "candidate=$index")
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
            val data = contractClient(appId, algodUrl = algodUrl).getSessionDynamicData(channelId, algodUrl).getOrThrow()
            SessionDynamicData(data.totalDeposit, data.lastSettled, data.latestVoucherAmount)
        }.onFailure {
            Napier.e("[SESSION_VAULT_DYNAMIC_ERR] appId=$appId context=${logContext.orEmpty()}", it, tag = TAG)
        }.getOrNull()

    fun deriveChannelId(
        viewerAddress: String,
        hostAddress: String,
        authorizedSignerPublicKey: ByteArray = decodeAlgorandAddressPublicKey(viewerAddress),
        usdcAssetId: Long = AssetConstants.USDC_TESTNET_ID,
        salt: ByteArray = CHANNEL_ID_SALT,
    ): ByteArray =
        contractClient(usdcAssetId = usdcAssetId).deriveChannelId(
            payerAddress = viewerAddress,
            payeeAddress = hostAddress,
            authorizedSignerPublicKey = authorizedSignerPublicKey,
            salt = salt,
        )

    fun buildClaimMessage(
        appId: Long,
        channelId: ByteArray,
        totalAmountClaimedMicroUsdc: Long,
    ): ByteArray = encodeUint64(appId) + channelId + encodeUint64(totalAmountClaimedMicroUsdc) + "settle".encodeToByteArray()

    fun buildClaimMessage(
        appId: Long,
        viewerAddress: String,
        hostAddress: String,
        totalAmountClaimedMicroUsdc: Long,
        authorizedSignerPublicKey: ByteArray = decodeAlgorandAddressPublicKey(viewerAddress),
    ): ByteArray =
        buildClaimMessage(appId, deriveChannelId(viewerAddress, hostAddress, authorizedSignerPublicKey), totalAmountClaimedMicroUsdc)

    @OptIn(ExperimentalEncodingApi::class)
    fun serializeVoucherSignature(signature: ByteArray): String = Base64.encode(signature)

    fun buildClaimMessageHashHex(
        appId: Long,
        channelId: ByteArray,
        totalAmountClaimedMicroUsdc: Long,
    ): String = hashHex(buildClaimMessage(appId, channelId, totalAmountClaimedMicroUsdc))

    fun buildClaimMessageHashHex(
        appId: Long,
        viewerAddress: String,
        hostAddress: String,
        totalAmountClaimedMicroUsdc: Long,
    ): String =
        buildClaimMessageHashHex(
            appId,
            deriveChannelId(viewerAddress, hostAddress, decodeAlgorandAddressPublicKey(viewerAddress)),
            totalAmountClaimedMicroUsdc,
        )

    fun signClaimMessageWithAlgoSdk(
        secretKey: ByteArray,
        appId: Long,
        channelId: ByteArray,
        totalAmountClaimedMicroUsdc: Long,
    ): ByteArray? =
        runCatching {
            signEd25519(secretKey, buildClaimMessage(appId, channelId, totalAmountClaimedMicroUsdc))
        }.onFailure {
            Napier.e("[CLAIM_SIGN_ERR] appId=$appId keySize=${secretKey.size}", it, tag = TAG)
        }.getOrNull()

    fun hashHex(bytes: ByteArray): String =
        sha256(bytes).joinToString("") {
            val v = it.toInt() and 0xFF
            "${HEX_CHARS[v ushr 4]}${HEX_CHARS[v and 0xF]}"
        }

    private val HEX_CHARS = "0123456789abcdef"

    fun verifyClaimSignatureLocally(
        viewerAddress: String,
        appId: Long,
        channelId: ByteArray,
        totalAmountClaimedMicroUsdc: Long,
        signature: ByteArray,
    ): Boolean {
        val publicKey = decodeAlgorandAddressPublicKey(viewerAddress)
        if (publicKey.size != 32 || signature.size != 64) return false
        return verifyEd25519(publicKey, buildClaimMessage(appId, channelId, totalAmountClaimedMicroUsdc), signature)
    }

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
        authorizedSignerPublicKey: ByteArray = decodeAlgorandAddressPublicKey(viewerAddress),
        algodUrl: String = TESTNET_ALGOD_URL,
    ): Result<VerifyClaimVoucherOnChainResult> =
        runCatching {
            val channelId = deriveChannelId(viewerAddress, hostAddress, authorizedSignerPublicKey)
            Napier.d("[VERIFY_HELPER_BUILD] appId=$appId viewer=$viewerAddress amount=$totalAmountClaimedMicroUsdc", tag = TAG)

            val txId =
                contractClient(appId, algodUrl = algodUrl)
                    .verifySettleSignatureOnChain(signer, channelId, totalAmountClaimedMicroUsdc, signature, algodUrl)
                    .getOrThrow()

            val (confirmedRound, logCount) = awaitConfirmationDetailsInternal(txId, algodUrl)
            VerifyClaimVoucherOnChainResult(txId = txId, verified = true, confirmedRound = confirmedRound, logCount = logCount)
        }.onFailure {
            Napier.e("[VERIFY_HELPER_ERR] appId=$appId viewer=$viewerAddress amount=$totalAmountClaimedMicroUsdc", it, tag = TAG)
        }

    fun awaitTransactionConfirmation(
        txId: String,
        algodUrl: String = TESTNET_ALGOD_URL,
        maxRounds: Int = 10,
    ): Boolean = awaitConfirmationInternal(txId, algodUrl, maxRounds)
}

sealed class MppPaymentVerificationResult {
    data class Valid(
        val senderAddress: String,
        val signedTransactionBytes: ByteArray,
        val transactionId: String,
    ) : MppPaymentVerificationResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Valid) return false
            return senderAddress == other.senderAddress &&
                signedTransactionBytes.contentEquals(other.signedTransactionBytes) &&
                transactionId == other.transactionId
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
