package com.michaeltchuang.walletsdk.ui.liquidStream

import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAlgo25SecretKey
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetFalcon24SecretKey
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetHdSeed
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetLocalAccount
import com.michaeltchuang.walletsdk.core.algosdk.signAlgo25ArbitraryData
import com.michaeltchuang.walletsdk.core.algosdk.signFalcon24ArbitraryData
import com.michaeltchuang.walletsdk.core.algosdk.signFalcon24GroupBundle
import com.michaeltchuang.walletsdk.core.algosdk.signHdKeyArbitraryData
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.smartcontract.EscrowSessionVaultManagerClient
import com.michaeltchuang.walletsdk.core.railmpp.utils.MppPayments
import com.michaeltchuang.walletsdk.core.railmpp.utils.RailMppConstants
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class IosViewerPaymentOrchestrator(
    private val getLocalAccount: GetLocalAccount,
    private val getAlgo25SecretKey: GetAlgo25SecretKey,
    private val getFalcon24SecretKey: GetFalcon24SecretKey,
    private val getHdSeed: GetHdSeed,
) {
    companion object {
        private const val TAG = "IosViewerPaymentOrch"
    }

    private val scope = CoroutineScope(Dispatchers.Default)

    // ── Public entry point ────────────────────────────────────────────────────

    fun depositAndSendVoucher(
        viewerAddress: String,
        hostAddress: String,
        sessionId: String,
        depositMicroUsdc: Long,
        sendMessageFn: (message: String) -> Unit,
        onResult: (remainingMicroUsdc: Long?) -> Unit,
    ) {
        scope.launch {
            try {
                val signer = buildWalletSigner(viewerAddress)
                if (signer == null) {
                    Napier.e("[VIEWER_DEPOSIT_SKIP] reason=no_signer viewer=$viewerAddress", tag = TAG)
                    onResult(null)
                    return@launch
                }

                // Check whether a session vault already exists for this pair.
                val existingBalance =
                    runCatching {
                        MppPayments.getRemainingBalanceFromSessionVault(
                            viewerAddress = viewerAddress,
                            hostAddress = hostAddress,
                            appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                            algodUrl = null,
                        )
                    }.getOrDefault(0L)

                Napier.d(
                    "[VIEWER_DEPOSIT_START] viewer=$viewerAddress host=$hostAddress " +
                        "existingBalance=$existingBalance depositMicroUsdc=$depositMicroUsdc",
                    tag = TAG,
                )

                val depositResult =
                    if (existingBalance > 0L) {
                        // Top up an existing session vault.
                        Napier.d("[VIEWER_DEPOSIT_TOPUP] viewer=$viewerAddress", tag = TAG)
                        MppPayments.topUpSessionVault(
                            signer = signer,
                            additionalDepositMicroUsdc = depositMicroUsdc,
                        )
                    } else {
                        // Open a new session vault and deposit.
                        Napier.d("[VIEWER_DEPOSIT_OPEN] viewer=$viewerAddress", tag = TAG)
                        val openResult =
                            MppPayments.openSessionAndDeposit(
                                signer = signer,
                                viewerAddress = viewerAddress,
                                creatorAddress = hostAddress,
                                depositAmountMicroUsdc = depositMicroUsdc,
                            )
                        if (openResult.isSuccess) {
                            // Register the authorized signer so the host can verify vouchers.
                            MppPayments
                                .setAuthorizedSignerForSession(
                                    signer = signer,
                                    appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                                    viewerAddress = viewerAddress,
                                    hostAddress = hostAddress,
                                    authorizedSignerPublicKey =
                                        signer.authorizedSignerPublicKey
                                            ?: ByteArray(0),
                                ).onFailure { e ->
                                    Napier.e(
                                        "[VIEWER_SET_SIGNER_ERR] viewer=$viewerAddress host=$hostAddress",
                                        e,
                                        tag = TAG,
                                    )
                                }
                        }
                        openResult
                    }

                depositResult.onFailure { e ->
                    Napier.e(
                        "[VIEWER_DEPOSIT_ERR] viewer=$viewerAddress host=$hostAddress",
                        e,
                        tag = TAG,
                    )
                    onResult(null)
                    return@launch
                }

                // Fetch the post-deposit on-chain balance.
                val remaining =
                    runCatching {
                        MppPayments.getRemainingBalanceFromSessionVault(
                            viewerAddress = viewerAddress,
                            hostAddress = hostAddress,
                            appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                            algodUrl = null,
                        )
                    }.getOrDefault(depositMicroUsdc)

                Napier.d(
                    "[VIEWER_DEPOSIT_OK] viewer=$viewerAddress host=$hostAddress " +
                        "remaining=$remaining txId=${depositResult.getOrNull()}",
                    tag = TAG,
                )

                onResult(remaining)
            } catch (e: Exception) {
                Napier.e("[VIEWER_DEPOSIT_EXCEPTION] viewer=$viewerAddress", e, tag = TAG)
                onResult(null)
            }
        }
    }

    // ── Voucher helper ────────────────────────────────────────────────────────

    suspend fun sendVoucherForReceipt(
        signer: MppWalletSigner,
        viewerAddress: String,
        hostAddress: String,
        sessionId: String,
        totalAmountClaimedMicroUsdc: Long,
        segmentDebitMicroUsdc: Long,
        remainingMicroUsdc: Long,
        sendMessageFn: (String) -> Unit,
    ) = trySendVoucher(
        signer = signer,
        viewerAddress = viewerAddress,
        hostAddress = hostAddress,
        sessionId = sessionId,
        totalAmountClaimedMicroUsdc = totalAmountClaimedMicroUsdc,
        segmentDebitMicroUsdc = segmentDebitMicroUsdc,
        remainingMicroUsdc = remainingMicroUsdc,
        sendMessageFn = sendMessageFn,
    )

    private suspend fun trySendVoucher(
        signer: MppWalletSigner,
        viewerAddress: String,
        hostAddress: String,
        sessionId: String,
        totalAmountClaimedMicroUsdc: Long,
        segmentDebitMicroUsdc: Long,
        remainingMicroUsdc: Long,
        sendMessageFn: (String) -> Unit,
    ) {
        try {
            val pubKey =
                signer.authorizedSignerPublicKey ?: run {
                    Napier.w("[VIEWER_VOUCHER_SKIP] reason=no_pub_key viewer=$viewerAddress", tag = TAG)
                    return
                }

            val preUpdateLatestVoucher =
                runCatching {
                    MppPayments
                        .getSessionDynamicDataFromVault(
                            viewerAddress = viewerAddress,
                            hostAddress = hostAddress,
                            appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                            authorizedSignerPublicKey = pubKey,
                        )?.latestVoucherAmount ?: 0L
                }.getOrDefault(0L)

            val localBase = (totalAmountClaimedMicroUsdc - segmentDebitMicroUsdc).coerceAtLeast(0L)
            val voucherBase = maxOf(localBase, preUpdateLatestVoucher)
            val effectiveClaimedMicroUsdc = (voucherBase + segmentDebitMicroUsdc).coerceAtLeast(1L)

            Napier.d(
                "[VIEWER_VOUCHER_AMOUNT_CALC] session=$sessionId " +
                    "localCumulative=$totalAmountClaimedMicroUsdc debit=$segmentDebitMicroUsdc " +
                    "preOnChainLatest=$preUpdateLatestVoucher voucherBase=$voucherBase " +
                    "effectiveClaimed=$effectiveClaimedMicroUsdc",
                tag = TAG,
            )
            val channelId = EscrowSessionVaultManagerClient.channelId
            if (channelId == null) {
                Napier.w("[VIEWER_VOUCHER_SKIP] reason=no_channel_id viewer=$viewerAddress", tag = TAG)
                return
            }

            val claimMessage =
                MppPayments.buildClaimMessage(
                    appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                    totalAmountClaimedMicroUsdc = effectiveClaimedMicroUsdc,
                    channelId = channelId,
                )

            val signature =
                signClaimMessage(claimMessage, signer, viewerAddress) ?: run {
                    Napier.w("[VIEWER_VOUCHER_SKIP] reason=signing_failed viewer=$viewerAddress", tag = TAG)
                    return
                }

            val signatureBase64 = MppPayments.serializeVoucherSignature(signature)

            val updateResult =
                runCatching {
                    MppPayments.updateVoucherOnChain(
                        signer = signer,
                        appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                        viewerAddress = viewerAddress,
                        hostAddress = hostAddress,
                        totalAmountUsedMicroUsdc = effectiveClaimedMicroUsdc,
                        signature = signature,
                        authorizedSignerPublicKey = pubKey,
                    )
                }.getOrElse { Result.failure(it) }

            val updateTxId =
                updateResult
                    .onSuccess { txId ->
                        Napier.d(
                            "[VIEWER_UPDATE_VOUCHER_OK] session=$sessionId txId=$txId " +
                                "effectiveClaimed=$effectiveClaimedMicroUsdc viewer=$viewerAddress host=$hostAddress",
                            tag = TAG,
                        )
                    }.onFailure { err ->
                        val errText = err.message.orEmpty()
                        val isDuplicate = MppPayments.isDuplicateVoucherUpdateError(errText)
                        if (isDuplicate) {
                            Napier.d(
                                "[VIEWER_UPDATE_VOUCHER_DUPLICATE_SKIP] session=$sessionId " +
                                    "effectiveClaimed=$effectiveClaimedMicroUsdc reason=already_recorded_onchain",
                                tag = TAG,
                            )
                        } else {
                            Napier.e(
                                "[VIEWER_UPDATE_VOUCHER_ERR] session=$sessionId " +
                                    "effectiveClaimed=$effectiveClaimedMicroUsdc viewer=$viewerAddress host=$hostAddress",
                                err,
                                tag = TAG,
                            )
                        }
                    }.getOrNull()

            val txConfirmed =
                if (updateTxId != null) {
                    withContext(Dispatchers.Default) {
                        MppPayments.awaitTransactionConfirmation(
                            txId = updateTxId,
                            algodUrl = MppPayments.TESTNET_ALGOD_URL,
                        )
                    }.also { confirmed ->
                        if (confirmed) {
                            Napier.d(
                                "[VIEWER_UPDATE_VOUCHER_CONFIRMED] session=$sessionId txId=$updateTxId",
                                tag = TAG,
                            )
                        } else {
                            Napier.w(
                                "[VIEWER_UPDATE_VOUCHER_UNCONFIRMED] session=$sessionId txId=$updateTxId",
                                tag = TAG,
                            )
                        }
                    }
                } else {
                    false
                }

            val onChainLatestVoucher =
                runCatching {
                    MppPayments
                        .getSessionDynamicDataFromVault(
                            viewerAddress = viewerAddress,
                            hostAddress = hostAddress,
                            appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                            authorizedSignerPublicKey = pubKey,
                        )?.latestVoucherAmount ?: 0L
                }.getOrDefault(0L)
            val duplicateVoucherUpdate =
                MppPayments.isDuplicateVoucherUpdateError(updateResult.exceptionOrNull()?.message.orEmpty())
            val caughtUp = onChainLatestVoucher >= effectiveClaimedMicroUsdc
            val effectiveUpdateOk = updateResult.isSuccess || txConfirmed || (duplicateVoucherUpdate && caughtUp)

            if (effectiveUpdateOk) {
                val voucherJson =
                    MppPayments.createVoucherJson(
                        sessionId = sessionId,
                        viewerAddress = viewerAddress,
                        viewerPublicKey = pubKey,
                        creatorAddress = hostAddress,
                        blocksConsumed = 1,
                        totalAmountUsed = effectiveClaimedMicroUsdc,
                        remainingMicroUsdc = remainingMicroUsdc,
                        signatureBase64 = signatureBase64,
                    )

                Napier.d(
                    "[VIEWER_VOUCHER_SEND] session=$sessionId viewer=$viewerAddress " +
                        "effectiveClaimed=$effectiveClaimedMicroUsdc sig=${signatureBase64.take(16)}...",
                    tag = TAG,
                )
                sendMessageFn(voucherJson)
            } else {
                val lagMicroUsdc = (effectiveClaimedMicroUsdc - onChainLatestVoucher).coerceAtLeast(0L)
                Napier.w(
                    "[VIEWER_VOUCHER_SEND_SKIP] session=$sessionId viewer=$viewerAddress " +
                        "effectiveClaimed=$effectiveClaimedMicroUsdc onChainLatestVoucher=$onChainLatestVoucher " +
                        "lagMicroUsdc=$lagMicroUsdc reason=update_not_confirmed",
                    tag = TAG,
                )
            }
        } catch (e: Exception) {
            Napier.e("[VIEWER_VOUCHER_ERR] viewer=$viewerAddress", e, tag = TAG)
        }
    }

    // ── Signing helpers ───────────────────────────────────────────────────────

    private suspend fun signClaimMessage(
        claimMessage: ByteArray,
        signer: MppWalletSigner,
        address: String,
    ): ByteArray? {
        val localAccount = getLocalAccount(address) ?: return null
        return try {
            when (localAccount) {
                is LocalAccount.Algo25 -> {
                    val secretKey = getAlgo25SecretKey(address) ?: return null
                    signAlgo25ArbitraryData(claimMessage, secretKey)
                }
                is LocalAccount.HdKey -> {
                    val seed = getHdSeed(localAccount.seedId) ?: return null
                    signHdKeyArbitraryData(
                        data = claimMessage,
                        seed = seed,
                        account = localAccount.account,
                        change = localAccount.change,
                        key = localAccount.keyIndex,
                    )
                }
                is LocalAccount.Falcon24 -> {
                    val secretKey = getFalcon24SecretKey(address) ?: return null
                    signFalcon24ArbitraryData(
                        data = claimMessage,
                        publicKey = localAccount.publicKey,
                        privateKey = secretKey,
                    )
                }
                else -> null
            }
        } catch (e: Exception) {
            Napier.e("[VIEWER_CLAIM_SIGN_ERR] viewer=$address", e, tag = TAG)
            null
        }
    }

    // ── Signer builder (same pattern as IosPaymentTestViewModel) ─────────────

    internal suspend fun buildWalletSigner(address: String): MppWalletSigner? {
        val localAccount = getLocalAccount(address) ?: return null
        if (localAccount is LocalAccount.SeedVault) return null

        val authorizedSignerPublicKey: ByteArray =
            when (localAccount) {
                is LocalAccount.HdKey -> localAccount.publicKey
                is LocalAccount.Falcon24 -> localAccount.publicKey
                is LocalAccount.Algo25 -> {
                    val secretKey = getAlgo25SecretKey(address)
                    if (secretKey != null && secretKey.size == 64) {
                        secretKey.copyOfRange(32, 64)
                    } else {
                        ByteArray(0)
                    }
                }
                else -> ByteArray(0)
            }

        val signerType: Long = if (localAccount is LocalAccount.Falcon24) 1L else 0L

        return object : MppWalletSigner {
            override val address: String = address
            override val authorizedSignerPublicKey: ByteArray = authorizedSignerPublicKey
            override val signerType: Long = signerType

            override suspend fun signTransactionBytes(txnMsgpack: ByteArray): ByteArray {
                return try {
                    when (localAccount) {
                        is LocalAccount.Algo25 -> {
                            val secretKey = getAlgo25SecretKey(address) ?: return ByteArray(0)
                            com.michaeltchuang.walletsdk.core.algosdk.signAlgo25Transaction(
                                secretKey = secretKey,
                                transactionByteArray = txnMsgpack,
                            )
                        }
                        is LocalAccount.HdKey -> {
                            val seed = getHdSeed(localAccount.seedId) ?: return ByteArray(0)
                            com.michaeltchuang.walletsdk.core.algosdk.signHdKeyTransaction(
                                transactionByteArray = txnMsgpack,
                                seed = seed,
                                account = localAccount.account,
                                change = localAccount.change,
                                key = localAccount.keyIndex,
                            ) ?: ByteArray(0)
                        }
                        is LocalAccount.Falcon24 -> {
                            val secretKey = getFalcon24SecretKey(address) ?: return ByteArray(0)
                            com.michaeltchuang.walletsdk.core.algosdk.signFalcon24Transaction(
                                transactionByteArray = txnMsgpack,
                                publicKey = localAccount.publicKey,
                                privateKey = secretKey,
                            ) ?: ByteArray(0)
                        }
                        else -> ByteArray(0)
                    }
                } catch (t: Throwable) {
                    Napier.e("signTransactionBytes failed for $address: ${t.message}", t, tag = TAG)
                    ByteArray(0)
                }
            }

            override suspend fun signTransactionsBytes(txnsMsgpack: List<ByteArray>): List<ByteArray> {
                if (localAccount !is LocalAccount.Falcon24 || txnsMsgpack.size <= 1) {
                    return super.signTransactionsBytes(txnsMsgpack)
                }
                return try {
                    val secretKey = getFalcon24SecretKey(address)
                    if (secretKey == null) {
                        Napier.e("[VIEWER_FALCON_BUNDLE] missing key for $address", tag = TAG)
                        return txnsMsgpack.map { ByteArray(0) }
                    }
                    val result =
                        signFalcon24GroupBundle(
                            txnsByteArrays = txnsMsgpack,
                            publicKey = localAccount.publicKey,
                            privateKey = secretKey,
                        )
                    if (result.isEmpty()) {
                        Napier.e("[VIEWER_FALCON_BUNDLE] bundle returned empty for $address", tag = TAG)
                        txnsMsgpack.map { ByteArray(0) }
                    } else {
                        Napier.d("[VIEWER_FALCON_BUNDLE] signed ${result.size} txns (includes dummies)", tag = TAG)
                        result
                    }
                } catch (t: Throwable) {
                    Napier.e("[VIEWER_FALCON_BUNDLE] failed for $address: ${t.message}", t, tag = TAG)
                    txnsMsgpack.map { ByteArray(0) }
                }
            }
        }
    }
}
