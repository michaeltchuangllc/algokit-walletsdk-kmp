package com.michaeltchuang.walletsdk.ui.liquidStream

import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAlgo25SecretKey
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetFalcon24SecretKey
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetHdSeed
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetLocalAccount
import com.michaeltchuang.walletsdk.core.algosdk.signAlgo25ArbitraryData
import com.michaeltchuang.walletsdk.core.algosdk.signFalcon24ArbitraryData
import com.michaeltchuang.walletsdk.core.algosdk.signHdKeyArbitraryData
import com.michaeltchuang.walletsdk.core.railmpp.MppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.utils.MppPayments
import com.michaeltchuang.walletsdk.core.railmpp.utils.RailMppConstants
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * iOS service that handles the complete viewer deposit flow:
 *
 * 1. Builds a [MppWalletSigner] from the local iOS keychain account.
 * 2. Opens (or tops up) the session vault on Algorand Testnet.
 * 3. Sets the authorized signer for the session.
 * 4. Fetches the updated on-chain remaining balance.
 * 5. Builds and signs a `liquid:payment:voucher` message and forwards it to the host
 *    via [sendMessageFn] so the Android host can settle the voucher on-chain.
 *
 * Registered in the iOS Koin graph via [UiPlatformModule.ios.kt].
 */
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

    /**
     * Performs the full deposit + voucher flow for the iOS viewer.
     *
     * Called from the `iosViewerDepositHandler` set in [App.ios.kt].
     *
     * @param viewerAddress Algorand address of the viewer.
     * @param hostAddress Algorand address of the creator / host.
     * @param sessionId The active payment session id (from `liquid:payment:request`).
     * @param depositMicroUsdc Amount to deposit in micro-USDC (1 USDC = 1_000_000).
     * @param sendMessageFn Callback to send a message to the host via the main data channel.
     * @param onResult Called with the new on-chain remaining balance, or null on failure.
     */
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
                val existingBalance = runCatching {
                    MppPayments.getRemainingBalanceFromSessionVault(
                        viewerAddress = viewerAddress,
                        hostAddress = hostAddress,
                        appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                        algodUrl = null,
                        authorizedSignerPublicKey = signer.authorizedSignerPublicKey,
                    )
                }.getOrDefault(0L)

                Napier.d(
                    "[VIEWER_DEPOSIT_START] viewer=$viewerAddress host=$hostAddress " +
                        "existingBalance=$existingBalance depositMicroUsdc=$depositMicroUsdc",
                    tag = TAG,
                )

                val depositResult = if (existingBalance > 0L) {
                    // Top up an existing session vault.
                    Napier.d("[VIEWER_DEPOSIT_TOPUP] viewer=$viewerAddress", tag = TAG)
                    MppPayments.topUpSessionVault(
                        signer = signer,
                        viewerAddress = viewerAddress,
                        hostAddress = hostAddress,
                        additionalDepositMicroUsdc = depositMicroUsdc,
                    )
                } else {
                    // Open a new session vault and deposit.
                    Napier.d("[VIEWER_DEPOSIT_OPEN] viewer=$viewerAddress", tag = TAG)
                    val openResult = MppPayments.openSessionAndDeposit(
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
                                authorizedSignerPublicKey = signer.authorizedSignerPublicKey
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
                val remaining = runCatching {
                    MppPayments.getRemainingBalanceFromSessionVault(
                        viewerAddress = viewerAddress,
                        hostAddress = hostAddress,
                        appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                        algodUrl = null,
                        authorizedSignerPublicKey = signer.authorizedSignerPublicKey,
                    )
                }.getOrDefault(depositMicroUsdc)

                Napier.d(
                    "[VIEWER_DEPOSIT_OK] viewer=$viewerAddress host=$hostAddress " +
                        "remaining=$remaining txId=${depositResult.getOrNull()}",
                    tag = TAG,
                )

                // Build and send a voucher to the host so it can settle on-chain and
                // transition from WaitingForPayment → Streaming state.
                trySendVoucher(
                    signer = signer,
                    viewerAddress = viewerAddress,
                    hostAddress = hostAddress,
                    sessionId = sessionId,
                    totalAmountClaimedMicroUsdc = depositMicroUsdc,
                    remainingMicroUsdc = remaining,
                    sendMessageFn = sendMessageFn,
                )

                onResult(remaining)
            } catch (e: Exception) {
                Napier.e("[VIEWER_DEPOSIT_EXCEPTION] viewer=$viewerAddress", e, tag = TAG)
                onResult(null)
            }
        }
    }

    // ── Voucher helper ────────────────────────────────────────────────────────

    private suspend fun trySendVoucher(
        signer: MppWalletSigner,
        viewerAddress: String,
        hostAddress: String,
        sessionId: String,
        totalAmountClaimedMicroUsdc: Long,
        remainingMicroUsdc: Long,
        sendMessageFn: (String) -> Unit,
    ) {
        try {
            val pubKey = signer.authorizedSignerPublicKey ?: run {
                Napier.w("[VIEWER_VOUCHER_SKIP] reason=no_pub_key viewer=$viewerAddress", tag = TAG)
                return
            }

            val claimMessage = MppPayments.buildClaimMessage(
                appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                viewerAddress = viewerAddress,
                hostAddress = hostAddress,
                totalAmountClaimedMicroUsdc = totalAmountClaimedMicroUsdc,
                authorizedSignerPublicKey = pubKey,
            )

            val signature = signClaimMessage(claimMessage, signer, viewerAddress) ?: run {
                Napier.w("[VIEWER_VOUCHER_SKIP] reason=signing_failed viewer=$viewerAddress", tag = TAG)
                return
            }

            val signatureBase64 = MppPayments.serializeVoucherSignature(signature)

            // Encode public key to base64 for the JSON field.
            val pubKeyBase64 = runCatching {
                kotlinx.io.bytestring.ByteString(pubKey)
                    .let { _ ->
                        @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
                        kotlin.io.encoding.Base64.encode(pubKey)
                    }
            }.getOrDefault("")

            val voucherJson = MppPayments.createVoucherJson(
                sessionId = sessionId,
                viewerAddress = viewerAddress,
                viewerPublicKey = pubKey,
                creatorAddress = hostAddress,
                blocksConsumed = 1,
                totalAmountUsed = totalAmountClaimedMicroUsdc,
                remainingMicroUsdc = remainingMicroUsdc,
                signatureBase64 = signatureBase64,
            )

            Napier.d(
                "[VIEWER_VOUCHER_SEND] session=$sessionId viewer=$viewerAddress " +
                    "claimed=$totalAmountClaimedMicroUsdc sig=${signatureBase64.take(16)}...",
                tag = TAG,
            )
            sendMessageFn(voucherJson)
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

        val authorizedSignerPublicKey: ByteArray = when (localAccount) {
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
        }
    }
}
