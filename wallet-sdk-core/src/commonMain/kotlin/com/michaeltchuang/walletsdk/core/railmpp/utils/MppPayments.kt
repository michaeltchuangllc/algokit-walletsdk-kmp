package com.michaeltchuang.walletsdk.core.railmpp.utils

import com.michaeltchuang.walletsdk.core.deeplink.utils.AssetConstants
import com.michaeltchuang.walletsdk.core.foundation.utils.LiquidStreamConstants
import com.michaeltchuang.walletsdk.core.railmpp.core.LiquidDcMessages
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.PaymentVoucher
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.internal.awaitConfirmationInternal
import com.michaeltchuang.walletsdk.core.railmpp.internal.decodeMsgPackAny
import com.michaeltchuang.walletsdk.core.railmpp.internal.encodeUint64
import com.michaeltchuang.walletsdk.core.railmpp.internal.sha256
import com.michaeltchuang.walletsdk.core.railmpp.smartcontract.EscrowSessionVaultManagerClient
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Top-level helper for Liquid Stream MPP payment operations. */
object MppPayments {
    private const val TAG = "MppPayments"

    const val MAINNET_ALGOD_URL = "https://mainnet-api.algonode.cloud"
    const val TESTNET_ALGOD_URL = "https://testnet-api.algonode.cloud"

    private const val DEPOSIT_MICRO_USDC_LONG = LiquidStreamConstants.DEPOSIT_AMOUNT_MICRO_USDC
    private const val COST_PER_BLOCK_MICRO_USDC = LiquidStreamConstants.COST_PER_BLOCK_MICRO_USDC
    private const val VOUCHER_SETTLE_EVERY_BLOCKS = 1

    fun algodUrlForAppId(appId: Long): String =
        when (appId) {
            RailMppConstants.MAINNET_MPP_SESSION_VAULT_APP_ID -> MAINNET_ALGOD_URL
            else -> TESTNET_ALGOD_URL
        }

    fun usdcAssetIdForAppId(appId: Long): Long =
        when (appId) {
            RailMppConstants.MAINNET_MPP_SESSION_VAULT_APP_ID -> AssetConstants.USDC_MAINNET_ID
            else -> AssetConstants.USDC_TESTNET_ID
        }

    fun decodeSignedTransaction(bytes: ByteArray): Any? =
        runCatching { decodeMsgPackAny(bytes) }
            .onFailure {
                Napier.e("Failed to decode signed transaction", it, tag = TAG)
            }.getOrNull()

    @OptIn(ExperimentalEncodingApi::class)
    fun createVoucherJson(
        sessionId: String,
        appId: Long = EscrowSessionVaultManagerClient.appId,
        viewerAddress: String,
        viewerPublicKey: ByteArray,
        creatorAddress: String,
        blocksConsumed: Int,
        totalAmountUsed: Long,
        remainingMicroUsdc: Long,
        signatureBase64: String? = null,
    ): String {
        val channelId =
            EscrowSessionVaultManagerClient.channelId
                ?.let(Base64::encode)

        return PaymentVoucher(
            reference = LiquidDcMessages.REF_PAYMENT_VOUCHER,
            id = sessionId,
            appId = appId,
            viewer = viewerAddress,
            viewerPublicKey = Base64.encode(viewerPublicKey),
            creator = creatorAddress,
            blocksWatched = blocksConsumed,
            costPerBlockMicroUsdc = COST_PER_BLOCK_MICRO_USDC,
            totalAmountClaimedMicroUsdc = totalAmountUsed,
            remainingMicroUsdc = remainingMicroUsdc,
            signature = signatureBase64,
            channelId = channelId,
        ).toJson()
    }

    fun shouldAttemptVoucherSettlement(blocksConsumed: Int): Boolean =
        blocksConsumed > 0 && blocksConsumed % VOUCHER_SETTLE_EVERY_BLOCKS == 0

    fun computeVoucherMicroUsdcUsage(blocksConsumed: Int): Long = (blocksConsumed.toLong() * COST_PER_BLOCK_MICRO_USDC).coerceAtLeast(0L)

    fun voucherSettleWindowMicroUsdc(): Long = (VOUCHER_SETTLE_EVERY_BLOCKS.toLong() * COST_PER_BLOCK_MICRO_USDC).coerceAtLeast(0L)

    fun estimateRemainingUsdcBalanceFromBlocks(blocksConsumed: Int): Long =
        (DEPOSIT_MICRO_USDC_LONG - blocksConsumed.toLong() * COST_PER_BLOCK_MICRO_USDC).coerceAtLeast(0L)

    fun remainingUsdcFromMicroAlgos(remainingMicroAlgos: Long): Double = remainingMicroAlgos / 1_000_000.0

    fun maxSessionDepositMicroUsdc(): Long = DEPOSIT_MICRO_USDC_LONG

    suspend fun getRemainingBalanceFromSessionVault(
        viewerAddress: String,
        hostAddress: String,
        authorizedSignerPublicKey: ByteArray? = null,
    ): Long {
        val baseContext = "viewer=$viewerAddress host=$hostAddress"
        val resolvedChannelId = EscrowSessionVaultManagerClient.channelId ?: return 0L
        val result = getRemainingBalanceFromSessionVaultByChannelId(resolvedChannelId, logContext = baseContext)
        Napier.e("[SESSION_VAULT_REMAINING_BALANCE_CHECK] result=${result ?: "null"}", tag = TAG)
        if (result != null) return result
        Napier.d("[SESSION_VAULT_REMAINING_MISS] appId=${EscrowSessionVaultManagerClient.appId} $baseContext", tag = TAG)
        return 0L
    }

    suspend fun getRemainingBalanceFromSessionVaultByChannelId(
        channelId: ByteArray,
        logContext: String? = null,
    ): Long? =
        withContext(Dispatchers.IO) {
            runCatching {
                val data =
                    EscrowSessionVaultManagerClient
                        .getSessionDynamicData(channelId)
                        .getOrThrow()
                (data.totalDeposit - data.lastSettled).coerceAtLeast(0L)
            }.onFailure {
                Napier.e("[SESSION_VAULT_REMAINING_ERR] appId=${EscrowSessionVaultManagerClient.appId} context=${logContext.orEmpty()}", it, tag = TAG)
            }.getOrNull()
        }

    suspend fun openSessionAndDeposit(
        signer: MppWalletSigner,
        viewerAddress: String,
        creatorAddress: String,
        depositAmountMicroUsdc: Long = DEPOSIT_MICRO_USDC_LONG,
    ): Result<String> {
        require(viewerAddress.isNotBlank()) { "viewerAddress is required" }
        require(creatorAddress.isNotBlank()) { "creatorAddress is required" }
        require(signer.address == viewerAddress) {
            "Session vault deposit signer must match viewerAddress"
        }
        Napier.d(
            "[OPEN_SESSION_DEPOSIT] viewer=$viewerAddress creator=$creatorAddress appId=${EscrowSessionVaultManagerClient.appId} usdcAssetId=${EscrowSessionVaultManagerClient.usdcAssetId} algodUrl=${EscrowSessionVaultManagerClient.algodUrl}",
            tag = TAG,
        )
        return EscrowSessionVaultManagerClient.openAndDeposit(
            signer = signer,
            payerAddress = viewerAddress,
            payeeAddress = creatorAddress,
            depositMicroUsdc = depositAmountMicroUsdc,
        )
    }

    suspend fun topUpSessionVault(
        signer: MppWalletSigner,
        additionalDepositMicroUsdc: Long,
    ): Result<String> {
        val channelId = EscrowSessionVaultManagerClient.channelId
            ?: return Result.failure(Exception("channelId is null"))
        return EscrowSessionVaultManagerClient.topUp(signer, channelId, additionalDepositMicroUsdc)
    }

    suspend fun setAuthorizedSignerForSession(
        signer: MppWalletSigner,
        viewerAddress: String,
        authorizedSignerPublicKey: ByteArray = signer.authorizedSignerPublicKey,
    ): Result<String> {
        val channelId = EscrowSessionVaultManagerClient.channelId
            ?: return Result.failure(Exception("channelId is null"))
        val result =
            EscrowSessionVaultManagerClient.setAuthorizedSignerPublicKey(
                signer,
                channelId,
                authorizedSignerPublicKey,
            )
        result
            .onSuccess { Napier.d("[VIEWER_SET_AUTH_SIGNER_OK] appId=${EscrowSessionVaultManagerClient.appId} txId=$it", tag = TAG) }
            .onFailure { Napier.e("[VIEWER_SET_AUTH_SIGNER_ERR] appId=${EscrowSessionVaultManagerClient.appId} viewer=$viewerAddress", it, tag = TAG) }
        return result
    }

    fun isDuplicateVoucherUpdateError(message: String): Boolean {
        val isKnownVoucherIncreaseAssert =
            message.contains("Voucher not increasing", ignoreCase = true) ||
                (
                    message.contains("opcodes=dig 2", ignoreCase = true) &&
                        message.contains("<; assert", ignoreCase = true)
                )
        val isKnownProgramCounter =
            message.contains("pc=622", ignoreCase = true) ||
                message.contains("pc=661", ignoreCase = true) ||
                message.contains("pc=662", ignoreCase = true)
        return isKnownVoucherIncreaseAssert && (isKnownProgramCounter || message.contains("Voucher not increasing", ignoreCase = true))
    }

    fun isNothingToSettleError(message: String): Boolean {
        val isKnownSettleAssert =
            message.contains("opcodes=dup2", ignoreCase = true) &&
                message.contains(">; assert", ignoreCase = true)
        val isKnownProgramCounter = message.contains("pc=890", ignoreCase = true)
        return isKnownSettleAssert && isKnownProgramCounter
    }

    suspend fun updateVoucherOnChain(
        signer: MppWalletSigner,
        viewerAddress: String,
        hostAddress: String,
        totalAmountUsedMicroUsdc: Long,
        signature: ByteArray,
    ): Result<String> {
        val channelId = EscrowSessionVaultManagerClient.channelId
            ?: return Result.failure(Exception("channelId is null"))
        val channelIdHash = hashHex(channelId).take(16)
        Napier.d(
            "[VIEWER_UPDATE_VOUCHER_ATTEMPT] appId=${EscrowSessionVaultManagerClient.appId} viewer=$viewerAddress host=$hostAddress claimedMicroUsdc=$totalAmountUsedMicroUsdc channelIdHash=$channelIdHash",
            tag = TAG,
        )
        val result =
            EscrowSessionVaultManagerClient.updateVoucher(
                signer,
                channelId,
                totalAmountUsedMicroUsdc,
                signature,
            )
        result
            .onSuccess { Napier.d("[VIEWER_UPDATE_VOUCHER_OK] txId=$it channelIdHash=$channelIdHash", tag = TAG) }
            .onFailure { throwable ->
                val err = throwable.message.orEmpty()
                val isDuplicate = isDuplicateVoucherUpdateError(err)
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

    suspend fun settleLatestVoucher(signer: MppWalletSigner): Result<String> {
        val channelId = EscrowSessionVaultManagerClient.channelId
            ?: return Result.failure(Exception("channelId is null"))
        return EscrowSessionVaultManagerClient.settleLatest(signer, channelId)
    }

    suspend fun settle(
        signer: MppWalletSigner,
        cumulativeAmountMicroUsdc: Long,
        signature: ByteArray,
    ): Result<String> {
        val channelId = EscrowSessionVaultManagerClient.channelId
            ?: return Result.failure(Exception("channelId is null"))
        return EscrowSessionVaultManagerClient.settle(signer, channelId, cumulativeAmountMicroUsdc, signature)
    }

    suspend fun verifySettleSignature(
        signer: MppWalletSigner,
        cumulativeAmountMicroUsdc: Long,
        signature: ByteArray,
    ): Result<String> {
        val channelId = EscrowSessionVaultManagerClient.channelId
            ?: return Result.failure(Exception("channelId is null"))
        return EscrowSessionVaultManagerClient.verifySettleSignature(signer, channelId, cumulativeAmountMicroUsdc, signature)
    }

    fun settleMessage(
        cumulativeAmountMicroUsdc: Long,
        channelId: ByteArray,
    ): ByteArray = EscrowSessionVaultManagerClient.settleMessage(channelId, cumulativeAmountMicroUsdc)

    suspend fun closeSessionVault(
        signer: MppWalletSigner,
        channelId: ByteArray,
    ): Result<String> =
        EscrowSessionVaultManagerClient.close(
            signer = signer,
            channelId = channelId,
        )

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

    suspend fun getSessionProgressSnapshotFromVault(): SessionProgressSnapshot? {
        val data = getSessionDynamicDataFromVault() ?: return null
        return computeSessionProgressSnapshot(data)
    }

    suspend fun getSessionDynamicDataFromVault(): SessionDynamicData? {
        val channelIdCandidates = listOf(EscrowSessionVaultManagerClient.channelId ?: return null)
        channelIdCandidates.forEachIndexed { index, channelId ->
            val result = getSessionDynamicDataFromVaultByChannelId(channelId, "candidate=$index")
            if (result != null) return result
        }
        return null
    }

    suspend fun getSessionDynamicDataFromVaultByChannelId(
        channelId: ByteArray,
        logContext: String? = null,
    ): SessionDynamicData? =
        withContext(Dispatchers.IO) {
            runCatching {
                val data = EscrowSessionVaultManagerClient.getSessionDynamicData(channelId).getOrThrow()
                SessionDynamicData(data.totalDeposit, data.lastSettled, data.latestVoucherAmount)
            }.onFailure {
                Napier.e("[SESSION_VAULT_DYNAMIC_ERR] appId=${EscrowSessionVaultManagerClient.appId} context=${logContext.orEmpty()}", it, tag = TAG)
            }.getOrNull()
        }

    fun buildClaimMessage(
        channelId: ByteArray,
        totalAmountClaimedMicroUsdc: Long,
    ): ByteArray = encodeUint64(EscrowSessionVaultManagerClient.appId) + channelId + encodeUint64(totalAmountClaimedMicroUsdc) + "settle".encodeToByteArray()

    @OptIn(ExperimentalEncodingApi::class)
    fun serializeVoucherSignature(signature: ByteArray): String = Base64.encode(signature)

    fun hashHex(bytes: ByteArray): String =
        sha256(bytes).joinToString("") {
            val v = it.toInt() and 0xFF
            "${HEX_CHARS[v ushr 4]}${HEX_CHARS[v and 0xF]}"
        }

    private val HEX_CHARS = "0123456789abcdef"

    fun awaitTransactionConfirmation(
        txId: String,
        maxRounds: Int = 10,
    ): Boolean = awaitConfirmationInternal(txId, EscrowSessionVaultManagerClient.algodUrl, maxRounds)
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
