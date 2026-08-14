package com.michaeltchuang.walletsdk.core.railmpp.utils

import com.michaeltchuang.walletsdk.core.deeplink.utils.AssetConstants
import com.michaeltchuang.walletsdk.core.foundation.utils.LiquidStreamConstants
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.DCMessageType
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.PaymentVoucher
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.internal.awaitConfirmationInternal
import com.michaeltchuang.walletsdk.core.railmpp.internal.encodeUint64
import com.michaeltchuang.walletsdk.core.railmpp.internal.sha256
import com.michaeltchuang.walletsdk.core.railmpp.smartcontract.EscrowSessionVaultHybridManagerClient
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Top-level helper for Liquid Stream MPP payment operations. */
object MppPayments {
    private const val TAG = "MppPayments"
    private const val DEPOSIT_MICRO_USDC_LONG = LiquidStreamConstants.DEPOSIT_AMOUNT_MICRO_USDC
    private const val COST_PER_BLOCK_MICRO_USDC = LiquidStreamConstants.COST_PER_BLOCK_MICRO_USDC
    private const val VOUCHER_SETTLE_EVERY_BLOCKS = 1

    fun usdcAssetIdForAppId(appId: Long): Long =
        when (appId) {
            RailMppConstants.MAINNET_MPP_SESSION_VAULT_APP_ID -> AssetConstants.USDC_MAINNET_ID
            RailMppConstants.FUTURENET_MPP_SESSION_VAULT_APP_ID -> AssetConstants.USDC_FUTURENET_ID
            else -> AssetConstants.USDC_TESTNET_ID
        }

    @OptIn(ExperimentalEncodingApi::class)
    fun createVoucherJson(
        sessionId: String,
        appId: Long = EscrowSessionVaultHybridManagerClient.appId,
        viewerAddress: String,
        viewerPublicKey: ByteArray,
        creatorAddress: String,
        blocksConsumed: Int,
        totalAmountUsed: Long,
        remainingMicroUsdc: Long,
        signatureBase64: String? = null,
    ): String {
        val channelId =
            EscrowSessionVaultHybridManagerClient.channelId
                ?.let(Base64::encode)

        return PaymentVoucher(
            type = DCMessageType.SEGMENT_VOUCHER.value,
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
        channelId: ByteArray? = EscrowSessionVaultHybridManagerClient.channelId,
    ): Long {
        val baseContext = "viewer=$viewerAddress"
        val resolvedChannelId = channelId ?: return 0L
        val result = getRemainingBalanceFromSessionVaultByChannelId(resolvedChannelId, logContext = baseContext)
        Napier.e("[SESSION_VAULT_REMAINING_BALANCE_CHECK] result=${result ?: "null"}", tag = TAG)
        if (result != null) return result
        Napier.d("[SESSION_VAULT_REMAINING_MISS] appId=${EscrowSessionVaultHybridManagerClient.appId} $baseContext", tag = TAG)
        return 0L
    }

    suspend fun getRemainingBalanceFromSessionVaultByChannelId(
        channelId: ByteArray,
        logContext: String? = null,
    ): Long? =
        withContext(Dispatchers.IO) {
            runCatching {
                val data =
                    EscrowSessionVaultHybridManagerClient
                        .getSessionDynamicData(channelId)
                        .getOrThrow()
                (data.totalDeposit - data.lastSettled).coerceAtLeast(0L)
            }.onFailure {
                Napier.e(
                    "[SESSION_VAULT_REMAINING_ERR] appId=${EscrowSessionVaultHybridManagerClient.appId} context=${logContext.orEmpty()}",
                    it,
                    tag = TAG,
                )
            }.getOrNull()
        }

    suspend fun openSessionAndDeposit(
        signer: MppWalletSigner,
        viewerAddress: String,
        depositAmountMicroUsdc: Long = DEPOSIT_MICRO_USDC_LONG,
        channelId: ByteArray? = EscrowSessionVaultHybridManagerClient.channelId,
    ): Result<String> {
        require(viewerAddress.isNotBlank()) { "viewerAddress is required" }
        require(signer.address == viewerAddress) {
            "Session vault deposit signer must match viewerAddress"
        }
        Napier.d(
            "[OPEN_SESSION_DEPOSIT] viewer=$viewerAddress appId=${EscrowSessionVaultHybridManagerClient.appId} usdcAssetId=${EscrowSessionVaultHybridManagerClient.usdcAssetId} algodUrl=${EscrowSessionVaultHybridManagerClient.algodUrl}",
            tag = TAG,
        )
        return EscrowSessionVaultHybridManagerClient.openAndDeposit(
            signer = signer,
            payerAddress = viewerAddress,
            depositMicroUsdc = depositAmountMicroUsdc,
            channelId = channelId,
        )
    }

    suspend fun topUpSessionVault(
        signer: MppWalletSigner,
        additionalDepositMicroUsdc: Long,
    ): Result<String> {
        val channelId =
            EscrowSessionVaultHybridManagerClient.channelId
                ?: return Result.failure(Exception("channelId is null"))
        return EscrowSessionVaultHybridManagerClient.topUp(signer, channelId, additionalDepositMicroUsdc)
    }

    suspend fun setAuthorizedSignerForSession(
        signer: MppWalletSigner,
        viewerAddress: String,
        authorizedSignerPublicKey: ByteArray = signer.authorizedSignerPublicKey,
        channelId: ByteArray? = null,
    ): Result<String> {
        val resolvedChannelId =
            channelId
                ?: EscrowSessionVaultHybridManagerClient.channelId
                ?: return Result.failure(Exception("channelId is null"))
        val result =
            EscrowSessionVaultHybridManagerClient.setAuthorizedSignerPublicKey(
                signer,
                resolvedChannelId,
                authorizedSignerPublicKey,
            )
        result
            .onSuccess { Napier.d("[VIEWER_SET_AUTH_SIGNER_OK] appId=${EscrowSessionVaultHybridManagerClient.appId} txId=$it", tag = TAG) }
            .onFailure {
                Napier.e(
                    "[VIEWER_SET_AUTH_SIGNER_ERR] appId=${EscrowSessionVaultHybridManagerClient.appId} viewer=$viewerAddress",
                    it,
                    tag = TAG,
                )
            }
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

    /** Registers the payer-created settlement LogicSig for a channel. */
    suspend fun setSettlementLogicSig(
        signer: MppWalletSigner,
        logicSigAddress: String,
        channelId: ByteArray? = EscrowSessionVaultHybridManagerClient.channelId,
    ): Result<String> {
        val resolvedChannelId =
            channelId
                ?: EscrowSessionVaultHybridManagerClient.channelId
                ?: return Result.failure(IllegalStateException("channelId is null"))
        return EscrowSessionVaultHybridManagerClient.setSettlementLogicSig(signer, resolvedChannelId, logicSigAddress)
    }

    /**
     * Compiles and registers the channel's settlement LogicSig, authorized with [signer]'s own
     * ephemeral session key. Must be called by the channel's payer (typically the viewer, right
     * after opening/topping-up the channel) — the contract asserts `Txn.sender === payer`.
     * Without this, [settleFromLogicSig] always fails with "not registered on-chain".
     */
    suspend fun registerSettlementLogicSig(
        signer: MppWalletSigner,
        payeeAddress: String = EscrowSessionVaultHybridManagerClient.hostAddress.orEmpty(),
        channelId: ByteArray? = EscrowSessionVaultHybridManagerClient.channelId,
    ): Result<String> {
        val resolvedChannelId =
            channelId
                ?: EscrowSessionVaultHybridManagerClient.channelId
                ?: return Result.failure(IllegalStateException("channelId is null"))
        if (payeeAddress.isBlank()) return Result.failure(IllegalStateException("payeeAddress is null/blank"))
        val result =
            EscrowSessionVaultHybridManagerClient.registerSettlementLogicSig(signer, resolvedChannelId, payeeAddress)
        result
            .onSuccess {
                Napier.d(
                    "[VIEWER_REGISTER_LOGIC_SIG_OK] appId=${EscrowSessionVaultHybridManagerClient.appId} txId=$it",
                    tag = TAG,
                )
            }.onFailure {
                Napier.e(
                    "[VIEWER_REGISTER_LOGIC_SIG_ERR] appId=${EscrowSessionVaultHybridManagerClient.appId} payee=$payeeAddress",
                    it,
                    tag = TAG,
                )
            }
        return result
    }

    /**
     * Emergency stop: payer immediately revokes the channel's registered settlement LogicSig
     * (e.g. if the ephemeral Falcon session key is suspected compromised) without closing the
     * channel or losing the deposit. [settleFromLogicSig] will fail until the payer registers a
     * fresh LogicSig via [setSettlementLogicSig].
     */
    suspend fun revokeSettlementLogicSig(
        signer: MppWalletSigner,
        channelId: ByteArray? = EscrowSessionVaultHybridManagerClient.channelId,
    ): Result<String> {
        val resolvedChannelId =
            channelId
                ?: EscrowSessionVaultHybridManagerClient.channelId
                ?: return Result.failure(IllegalStateException("channelId is null"))
        return EscrowSessionVaultHybridManagerClient.revokeSettlementLogicSig(signer, resolvedChannelId)
    }

    /**
     * Settles a viewer-signed voucher through the channel's registered custom LogicSig.
     * [voucherSignature] must sign [buildLogicSigSettlementVoucher] with the viewer's authorized
     * signer key; the payee only supplies the signed voucher and never needs that private key.
     */
    suspend fun settleFromLogicSig(
        funderSigner: MppWalletSigner,
        cumulativeAmountMicroUsdc: Long,
        voucherSignature: ByteArray,
        authorizedSignerPublicKey: ByteArray,
        payeeAddress: String,
        channelId: ByteArray? = EscrowSessionVaultHybridManagerClient.channelId,
        note: String = "N/A",
    ): Result<String> {
        val resolvedChannelId =
            channelId
                ?: EscrowSessionVaultHybridManagerClient.channelId
                ?: return Result.failure(IllegalStateException("channelId is null"))
        return EscrowSessionVaultHybridManagerClient.settleFromLogicSig(
            funderSigner = funderSigner,
            channelId = resolvedChannelId,
            cumulativeAmountMicroUsdc = cumulativeAmountMicroUsdc,
            voucherSignature = voucherSignature,
            authorizedSignerPublicKey = authorizedSignerPublicKey,
            payeeAddress = payeeAddress,
            note = note,
        )
    }

    fun buildLogicSigSettlementVoucher(
        channelId: ByteArray,
        cumulativeAmountMicroUsdc: Long,
        payeeAddress: String,
    ): ByteArray =
        encodeUint64(EscrowSessionVaultHybridManagerClient.appId) +
            channelId +
            encodeUint64(cumulativeAmountMicroUsdc) +
            com.michaeltchuang.walletsdk.core.railmpp.internal
                .decodeAlgorandAddressPublicKey(payeeAddress) +
            "settle-lsig-v1".encodeToByteArray()

    @Deprecated("The hybrid contract no longer has updateVoucher; use the LogicSig settlement flow.")
    suspend fun updateVoucherOnChain(
        signer: MppWalletSigner,
        viewerAddress: String,
        totalAmountUsedMicroUsdc: Long,
        signature: ByteArray,
        channelId: ByteArray? = EscrowSessionVaultHybridManagerClient.channelId,
    ): Result<String> =
        Result.failure(
            UnsupportedOperationException(
                "updateVoucher is not available on EscrowSessionVaultHybridManager; " +
                    "register a settlement LogicSig and use settleFromLogicSig instead.",
            ),
        )

    suspend fun closeSessionVault(
        signer: MppWalletSigner,
        channelId: ByteArray,
    ): Result<String> =
        EscrowSessionVaultHybridManagerClient.close(
            signer = signer,
            channelId = channelId,
        )

    data class SessionDynamicData(
        val totalDeposit: Long,
        val lastSettled: Long,
        val latestVoucherAmount: Long,
        val startRound: Long,
    ) {
        val unclaimedVoucherAmount: Long get() = (latestVoucherAmount - lastSettled).coerceAtLeast(0L)
    }

    data class SessionProgressSnapshot(
        val totalDepositMicroUsdc: Long,
        val remainingSettledMicroUsdc: Long,
        val progressBalanceMicroUsdc: Long,
        val lastSettledMicroUsdc: Long,
        val latestVoucherAmountMicroUsdc: Long,
        val startRound: Long,
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
            startRound = dynamicData.startRound,
        )
    }

    suspend fun getSessionProgressSnapshotFromVault(
        channelId: ByteArray? = EscrowSessionVaultHybridManagerClient.channelId,
    ): SessionProgressSnapshot? {
        val data = getSessionDynamicDataFromVault(channelId) ?: return null
        return computeSessionProgressSnapshot(data)
    }

    suspend fun getSessionDynamicDataFromVault(
        channelId: ByteArray? = EscrowSessionVaultHybridManagerClient.channelId,
    ): SessionDynamicData? {
        val resolvedChannelId = channelId ?: EscrowSessionVaultHybridManagerClient.channelId ?: return null
        val channelIdCandidates = listOf(resolvedChannelId)
        channelIdCandidates.forEachIndexed { index, candidate ->
            val result = getSessionDynamicDataFromVaultByChannelId(candidate, "candidate=$index")
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
                val data = EscrowSessionVaultHybridManagerClient.getSessionDynamicData(channelId).getOrThrow()
                val staticData = EscrowSessionVaultHybridManagerClient.getSessionStaticData(channelId).getOrThrow()
                SessionDynamicData(
                    totalDeposit = data.totalDeposit,
                    lastSettled = data.lastSettled,
                    latestVoucherAmount = data.latestVoucherAmount,
                    startRound = staticData.startRound,
                )
            }.onFailure {
                Napier.e(
                    "[SESSION_VAULT_DYNAMIC_ERR] appId=${EscrowSessionVaultHybridManagerClient.appId} context=${logContext.orEmpty()}",
                    it,
                    tag = TAG,
                )
            }.getOrNull()
        }

    fun buildClaimMessage(
        channelId: ByteArray,
        totalAmountClaimedMicroUsdc: Long,
    ): ByteArray =
        encodeUint64(EscrowSessionVaultHybridManagerClient.appId) + channelId + encodeUint64(totalAmountClaimedMicroUsdc) +
            "settle".encodeToByteArray()

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
    ): Boolean = awaitConfirmationInternal(txId, EscrowSessionVaultHybridManagerClient.algodUrl, maxRounds)
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
