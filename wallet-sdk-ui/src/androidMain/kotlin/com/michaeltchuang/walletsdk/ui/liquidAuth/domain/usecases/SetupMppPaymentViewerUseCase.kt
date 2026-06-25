package com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.michaeltchuang.walletsdk.core.liquidAuth.auth.connect.SignalService
import com.michaeltchuang.walletsdk.core.railmpp.LiquidStreamViewer
import com.michaeltchuang.walletsdk.core.railmpp.MppClientConfig
import com.michaeltchuang.walletsdk.core.railmpp.core.BudgetCap
import com.michaeltchuang.walletsdk.core.railmpp.core.ClientConfig
import com.michaeltchuang.walletsdk.core.railmpp.core.ConsentApproval
import com.michaeltchuang.walletsdk.core.railmpp.core.ConsentHandler
import com.michaeltchuang.walletsdk.core.railmpp.core.ConsentTerms
import com.michaeltchuang.walletsdk.core.railmpp.core.GatingMode
import com.michaeltchuang.walletsdk.core.railmpp.core.LiquidDcMessages
import com.michaeltchuang.walletsdk.core.railmpp.core.PAYMENT_CHANNEL_LABEL
import com.michaeltchuang.walletsdk.core.railmpp.data.repository.AndroidSessionVaultBalanceRepository
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.HelloMessage
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetRemainingSessionVaultBalanceUseCase
import com.michaeltchuang.walletsdk.core.railmpp.smartcontract.EscrowSessionVaultManagerClient
import com.michaeltchuang.walletsdk.core.railmpp.utils.MppPayments
import com.michaeltchuang.walletsdk.core.railmpp.utils.RailMppConstants
import com.michaeltchuang.walletsdk.core.railmpp.utils.toJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.webrtc.DataChannel
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.io.encoding.Base64

class SetupMppPaymentViewerUseCase(
    private val getRemainingSessionVaultBalanceUseCase: GetRemainingSessionVaultBalanceUseCase =
        GetRemainingSessionVaultBalanceUseCase(AndroidSessionVaultBalanceRepository()),
) {
    companion object {
        private const val TAG = "SetupMppPaymentViewer"
        private const val DISABLE_VIEWER_UPDATE_VOUCHER_FOR_DEBUG = false
        private const val CHAIN_WRITE_TIMEOUT_MS = 15_000L
    }

    data class Params(
        val signalService: SignalService?,
        val viewerAddress: String?,
        val hostAddress: String?,
        val scope: CoroutineScope,
        val buildMppWalletSigner: suspend (String) -> MppWalletSigner?,
        val resolveMppClientNetwork: suspend (String) -> String,
        val requestMppConsent: suspend (ConsentTerms) -> ConsentApproval,
        val setViewerSessionVaultProgress: (remainingBalanceMicroUsdc: Long, progressBalanceMicroUsdc: Long) -> Unit,
        val signFido2Challenge: suspend (challenge: ByteArray, address: String) -> ByteArray?,
    )

    private var liquidStreamViewer: LiquidStreamViewer? = null
    private var viewerOnChainRefreshJob: Job? = null
    private var viewerAuthorizedSignerPublicKey: ByteArray? = null
    private var viewerVoucherSessionId: String? = null
    private var viewerVoucherBlocksConsumed: Int = 0
    private var viewerVoucherClaimedMicroUsdc: Long = 0L
    private var viewerVoucherCapLoggedSessionId: String? = null

    operator fun invoke(params: Params) {
        Log.d(
            TAG,
            "[VIEWER_MPP_SETUP_START] viewer=${params.viewerAddress.orEmpty()} host=${params.hostAddress.orEmpty()} serviceReady=${params.signalService != null}",
        )
        val service =
            params.signalService
                ?: run {
                    Log.e(TAG, "[VIEWER_MPP_SETUP_SKIP] reason=missing_signal_service")
                    return
                }
        if (service.peerConnection == null) {
            Log.e(TAG, "[VIEWER_MPP_SETUP_SKIP] reason=missing_peer_connection")
            return
        }
        val viewerAddress =
            params.viewerAddress?.takeIf { it.isNotBlank() }
                ?: run {
                    Log.w(TAG, "[VIEWER_MPP_SETUP_SKIP] reason=blank_viewer host=${params.hostAddress.orEmpty()}")
                    return
                }
        val sessionVaultHostAddress =
            params.hostAddress?.takeIf { it.isNotBlank() }
                ?: run {
                    Log.w(TAG, "[VIEWER_MPP_SETUP_SKIP] reason=blank_host viewer=$viewerAddress")
                    return
                }

        params.scope.launch {
            try {
                Log.d(TAG, "[VIEWER_MPP_PAYMENT_CHANNEL_WAIT] viewer=$viewerAddress host=$sessionVaultHostAddress")
                val paymentChannel =
                    awaitPaymentDataChannel(service)
                        ?: run {
                            Log.e(
                                TAG,
                                "[VIEWER_MPP_SETUP_SKIP] reason=missing_payment_channel viewer=$viewerAddress host=$sessionVaultHostAddress",
                            )
                            return@launch
                        }
                Log.d(
                    TAG,
                    "[VIEWER_MPP_PAYMENT_CHANNEL_READY] viewer=$viewerAddress host=$sessionVaultHostAddress label=${paymentChannel.label()} state=${paymentChannel.state()}",
                )
                val signer = params.buildMppWalletSigner(viewerAddress)
                if (signer == null) {
                    Log.e(
                        TAG,
                        "[VIEWER_MPP_SETUP_SKIP] reason=missing_signer viewer=$viewerAddress host=$sessionVaultHostAddress",
                    )
                    return@launch
                }
                Log.d(TAG, "[VIEWER_MPP_SIGNER_READY] signerAddress=${signer.address} viewer=$viewerAddress")
                viewerAuthorizedSignerPublicKey = signer.authorizedSignerPublicKey

                Log.d(TAG, "[VIEWER_MPP_CLEANUP_PREVIOUS] viewer=$viewerAddress")
                stopViewerOnChainRefresh()
                liquidStreamViewer?.terminate()
                viewerVoucherSessionId = null
                viewerVoucherBlocksConsumed = 0
                viewerVoucherClaimedMicroUsdc = 0L
                viewerVoucherCapLoggedSessionId = null

                val mppNetwork = params.resolveMppClientNetwork(viewerAddress)
                Log.d(
                    TAG,
                    "[VIEWER_MPP_CREATE_VIEWER] viewer=$viewerAddress host=$sessionVaultHostAddress network=$mppNetwork",
                )
                liquidStreamViewer =
                    LiquidStreamViewer(
                        dataChannel = paymentChannel,
                        mppClientConfig =
                            MppClientConfig(
                                network = mppNetwork,
                                signer = signer,
                            ),
                        consentHandler =
                            object : ConsentHandler {
                                override suspend fun requestConsent(terms: ConsentTerms): ConsentApproval {
                                    Log.d(
                                        TAG,
                                        "[VIEWER_MPP_CONSENT_REQUEST] viewer=$viewerAddress host=$sessionVaultHostAddress amount=${terms.amount} asset=${terms.asset} network=${terms.network} gating=${terms.gatingMode}",
                                    )
                                    Log.d(
                                        TAG,
                                        "[VIEWER_SESSION_VAULT_FETCH_BEFORE_CONSENT] viewer=$viewerAddress host=$sessionVaultHostAddress",
                                    )
                                    val existingOnChainBalance =
                                        getRemainingSessionVaultBalanceUseCase(
                                            GetRemainingSessionVaultBalanceUseCase.Params(
                                                viewerAddress = viewerAddress,
                                                hostAddress = sessionVaultHostAddress,
                                                appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                                                authorizedSignerPublicKey = signer.authorizedSignerPublicKey,
                                            ),
                                        ).getOrDefault(0L)

                                    if (existingOnChainBalance > 0L) {
                                        params.setViewerSessionVaultProgress(existingOnChainBalance, existingOnChainBalance)
                                        Log.d(
                                            TAG,
                                            "[VIEWER_SESSION_VAULT_FUNDED] balanceMicroUsdc=$existingOnChainBalance balanceUsdc=${existingOnChainBalance / 1_000_000.0} action=skip_payment_modal",
                                        )
                                        startViewerOnChainRefresh(
                                            scope = params.scope,
                                            viewerAddress = viewerAddress,
                                            hostAddress = sessionVaultHostAddress,
                                            authorizedSignerPublicKey = signer.authorizedSignerPublicKey,
                                            setViewerSessionVaultProgress = params.setViewerSessionVaultProgress,
                                        )
                                        return ConsentApproval(
                                            approved = true,
                                            autoPaySegments = true,
                                            budgetCap =
                                                BudgetCap(
                                                    amount = existingOnChainBalance.toString(),
                                                    asset = "USDC",
                                                ),
                                        )
                                    }

                                    val approval = params.requestMppConsent(terms)
                                    Log.d(
                                        TAG,
                                        "[VIEWER_MPP_CONSENT_RESULT] viewer=$viewerAddress host=$sessionVaultHostAddress approved=${approval.approved} autoPay=${approval.autoPaySegments} budget=${approval.budgetCap?.amount}",
                                    )
                                    if (!approval.approved) {
                                        Log.w(
                                            TAG,
                                            "[VIEWER_MPP_CONSENT_REJECTED] viewer=$viewerAddress host=$sessionVaultHostAddress",
                                        )
                                        return approval
                                    }

                                    val depositMicroUsdc =
                                        approval.budgetCap
                                            ?.amount
                                            ?.toLongOrNull()
                                            ?.takeIf { it > 0L }
                                            ?: 1_000_000L

                                    Log.d(
                                        TAG,
                                        "[VIEWER_SESSION_VAULT_DEPOSIT_START] viewer=$viewerAddress host=$sessionVaultHostAddress amountMicroUsdc=$depositMicroUsdc",
                                    )
                                    MppPayments
                                        .openSessionAndDeposit(
                                            signer = signer,
                                            viewerAddress = viewerAddress,
                                            creatorAddress = sessionVaultHostAddress,
                                            depositAmountMicroUsdc = depositMicroUsdc,
                                        ).onSuccess { txId ->
                                            Log.d(TAG, "✅ Session Vault openSession+deposit txId=$txId")

                                            MppPayments
                                                .setAuthorizedSignerForSession(
                                                    signer = signer,
                                                    appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                                                    viewerAddress = viewerAddress,
                                                    hostAddress = sessionVaultHostAddress,
                                                    authorizedSignerPublicKey = signer.authorizedSignerPublicKey,
                                                ).onSuccess { signerTxId ->
                                                    Log.d(
                                                        TAG,
                                                        "✅ Session Vault setAuthorizedSignerPublicKey txId=$signerTxId viewer=$viewerAddress host=$sessionVaultHostAddress",
                                                    )
                                                }.onFailure {
                                                    Log.e(
                                                        TAG,
                                                        "❌ Session Vault setAuthorizedSignerPublicKey failed viewer=$viewerAddress host=$sessionVaultHostAddress",
                                                        it,
                                                    )
                                                }

                                            val onChainRemaining =
                                                getRemainingSessionVaultBalanceUseCase(
                                                    GetRemainingSessionVaultBalanceUseCase.Params(
                                                        viewerAddress = viewerAddress,
                                                        hostAddress = sessionVaultHostAddress,
                                                        appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                                                        authorizedSignerPublicKey = signer.authorizedSignerPublicKey,
                                                    ),
                                                ).getOrDefault(0L)
                                            Log.d(
                                                TAG,
                                                "[VIEWER_SESSION_VAULT_FETCH_AFTER_DEPOSIT] viewer=$viewerAddress remaining=$onChainRemaining",
                                            )
                                            params.setViewerSessionVaultProgress(onChainRemaining, onChainRemaining)
                                            startViewerOnChainRefresh(
                                                scope = params.scope,
                                                viewerAddress = viewerAddress,
                                                hostAddress = sessionVaultHostAddress,
                                                authorizedSignerPublicKey = signer.authorizedSignerPublicKey,
                                                setViewerSessionVaultProgress = params.setViewerSessionVaultProgress,
                                            )
                                        }.onFailure {
                                            Log.e(TAG, "❌ Session Vault openSession+deposit failed", it)
                                        }

                                    return approval
                                }
                            },
                        clientConfig = ClientConfig(autoPaySegments = false),
                    ).also { viewer ->
                        // ── Early key announcement ────────────────────────────────────────
                        // Send the viewer's authorized signer public key to the creator the
                        // moment the DataChannel opens — BEFORE any segment:request arrives.
                        viewer.rtcClient.onDataChannelOpen = {
                            val pubKeyBytes =
                                signer.authorizedSignerPublicKey
                                    .let(Base64::encode)
                            val helloMsg =
                                HelloMessage(
                                    reference = LiquidDcMessages.REF_VIEWER_HELLO,
                                    viewer = viewerAddress,
                                    viewerPublicKey = pubKeyBytes,
                                )

                            service.send(helloMsg.toJson())

                            Log.d(
                                TAG,
                                "[VIEWER_HELLO_SENT] viewer=$viewerAddress pubKeyLen=${pubKeyBytes.length}",
                            )
                        }

                        viewer.rtcClient.onPaymentRequested = { request ->
                            Log.d(
                                TAG,
                                "[VIEWER_PAYMENT_REQUEST_RECEIVED] session=${request.id} payTo=${request.payTo} amount=${request.amount} asset=${request.asset}",
                            )
                        }
                        viewer.rtcClient.onPaymentReceipt = { receipt ->
                            Log.d(
                                TAG,
                                "[VIEWER_PAYMENT_RECEIPT_CALLBACK] session=${receipt.sessionId} payFrom=${receipt.payFrom} payTo=${receipt.payTo} amount=${receipt.amount} segment=${receipt.segmentIndex}",
                            )
                            params.scope.launch {
                                val debit = receipt.amount.toLongOrNull() ?: 0L
                                val receiptViewerAddress = receipt.payFrom.ifBlank { viewerAddress }
                                val sessionId = receipt.sessionId
                                if (viewerVoucherSessionId != sessionId) {
                                    viewerVoucherSessionId = sessionId
                                    viewerVoucherBlocksConsumed = 0
                                    viewerVoucherClaimedMicroUsdc = 0L
                                    viewerVoucherCapLoggedSessionId = null
                                }
                                viewerVoucherBlocksConsumed += 1
                                val blocksConsumed = viewerVoucherBlocksConsumed.coerceAtLeast(0)
                                val voucherIncrement = debit.coerceAtLeast(0L)

                                Log.d(
                                    TAG,
                                    "💸 MPP receipt accepted: session=${receipt.sessionId} segment=${receipt.segmentIndex} amount=${receipt.amount} asset=${receipt.asset} txId=${receipt.txId}",
                                )

                                if (receiptViewerAddress.isNotBlank()) {
                                    val preUpdateDynamicData =
                                        safeApiCall("getSessionDynamicData.preUpdate") {
                                            MppPayments.getSessionDynamicDataFromVault(
                                                viewerAddress = receiptViewerAddress,
                                                hostAddress = sessionVaultHostAddress,
                                                appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                                                authorizedSignerPublicKey = signer.authorizedSignerPublicKey,
                                            )
                                        }
                                    val preUpdateLatestVoucher = preUpdateDynamicData?.latestVoucherAmount ?: 0L
                                    val preUpdateLastSettled = preUpdateDynamicData?.lastSettled ?: 0L
                                    val preUpdateTotalDeposit = preUpdateDynamicData?.totalDeposit ?: 0L
                                    val hasOnChainSessionData = preUpdateDynamicData != null && preUpdateTotalDeposit > 0L
                                    val voucherBase = maxOf(viewerVoucherClaimedMicroUsdc, preUpdateLatestVoucher)
                                    val voucherClaimedRaw = (voucherBase + voucherIncrement).coerceAtLeast(0L)

                                    val minRequiredCumulative = maxOf(preUpdateLatestVoucher, preUpdateLastSettled) + 1L
                                    val maxAllowedCumulative =
                                        if (hasOnChainSessionData) {
                                            preUpdateTotalDeposit
                                        } else {
                                            Long.MAX_VALUE
                                        }

                                    val voucherClaimed =
                                        voucherClaimedRaw
                                            .coerceAtLeast(minRequiredCumulative)
                                            .coerceAtMost(maxAllowedCumulative)

                                    if (voucherClaimedRaw > maxAllowedCumulative && viewerVoucherCapLoggedSessionId != receipt.sessionId) {
                                        viewerVoucherCapLoggedSessionId = receipt.sessionId
                                        Log.e(
                                            TAG,
                                            "[VIEWER_VOUCHER_CLAMP_DEPOSIT] session=${receipt.sessionId} claimedRaw=$voucherClaimedRaw clampedClaimed=$voucherClaimed maxAllowedCumulative=$maxAllowedCumulative totalDeposit=$preUpdateTotalDeposit viewer=$receiptViewerAddress host=$sessionVaultHostAddress",
                                        )
                                    }

                                    Log.e(
                                        TAG,
                                        "[VIEWER_VOUCHER_ASSERT_PRECHECK] session=${receipt.sessionId} lastSettled=$preUpdateLastSettled latestVoucher=$preUpdateLatestVoucher totalDeposit=$preUpdateTotalDeposit signedCumulative=$voucherClaimed",
                                    )

                                    viewerVoucherClaimedMicroUsdc = voucherClaimed

                                    val voucherSignature =
                                        runCatching {
                                            val message =
                                                MppPayments.buildClaimMessage(
                                                    appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                                                    totalAmountClaimedMicroUsdc = voucherClaimed,
                                                    channelId = EscrowSessionVaultManagerClient.Companion.channelId ?: return@launch,
                                                )
                                            params.signFido2Challenge(message, viewerAddress)
                                        }.getOrNull()

                                    if (voucherSignature != null && voucherSignature.isNotEmpty()) {
                                        if (DISABLE_VIEWER_UPDATE_VOUCHER_FOR_DEBUG) {
                                            Log.d(
                                                TAG,
                                                "[VIEWER_UPDATE_VOUCHER_DISABLED_DEBUG] session=${receipt.sessionId} segment=${receipt.segmentIndex} claimed=$voucherClaimed viewer=$receiptViewerAddress host=$sessionVaultHostAddress",
                                            )
                                        } else {
                                            val updateVoucherOnChain =
                                                suspend {
                                                    try {
                                                        Result.success(
                                                            withTimeout(CHAIN_WRITE_TIMEOUT_MS) {
                                                                MppPayments
                                                                    .updateVoucherOnChain(
                                                                        signer = signer,
                                                                        appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                                                                        viewerAddress = receiptViewerAddress,
                                                                        hostAddress = sessionVaultHostAddress,
                                                                        totalAmountUsedMicroUsdc = voucherClaimed,
                                                                        signature = voucherSignature,
                                                                    ).getOrThrow()
                                                            },
                                                        )
                                                    } catch (timeout: TimeoutCancellationException) {
                                                        Result.failure(
                                                            IllegalStateException(
                                                                "updateVoucher timeout after ${CHAIN_WRITE_TIMEOUT_MS}ms",
                                                                timeout,
                                                            ),
                                                        )
                                                    } catch (t: Throwable) {
                                                        Result.failure(t)
                                                    }
                                                }

                                            var updateResult =
                                                if (voucherClaimed <= preUpdateLatestVoucher) {
                                                    val skipReason =
                                                        if (voucherClaimed == preUpdateLatestVoucher) {
                                                            "already_onchain_equal"
                                                        } else {
                                                            "behind_latest"
                                                        }
                                                    Log.e(
                                                        TAG,
                                                        "[VIEWER_VOUCHER_PRECHECK_SKIP] session=${receipt.sessionId} claimed=$voucherClaimed onChainLatestVoucherMicroUsdc=$preUpdateLatestVoucher viewer=$receiptViewerAddress host=$sessionVaultHostAddress reason=$skipReason",
                                                    )
                                                    Result.success("SKIPPED_ALREADY_ONCHAIN")
                                                } else {
                                                    updateVoucherOnChain()
                                                }
                                            if (updateResult.isFailure) {
                                                val firstErrText = updateResult.exceptionOrNull()?.message.orEmpty()
                                                val duplicateVoucherUpdate =
                                                    MppPayments.isDuplicateVoucherUpdateError(firstErrText)
                                                if (!duplicateVoucherUpdate) {
                                                    Log.e(
                                                        TAG,
                                                        "[VIEWER_VOUCHER_UPDATE_RETRY] session=${receipt.sessionId} claimed=$voucherClaimed viewer=$receiptViewerAddress host=$sessionVaultHostAddress",
                                                        updateResult.exceptionOrNull(),
                                                    )
                                                    delay(350L)
                                                    updateResult = updateVoucherOnChain()
                                                }
                                            }

                                            if (updateResult.isFailure) {
                                                val errText = updateResult.exceptionOrNull()?.message.orEmpty()
                                                val missingSignerBox =
                                                    errText.contains("box_len; bury 1; assert", ignoreCase = true) ||
                                                        errText.contains("Authorized signer public key not set yet", ignoreCase = true)
                                                if (missingSignerBox) {
                                                    Log.e(
                                                        TAG,
                                                        "[VIEWER_VOUCHER_SET_SIGNER_RECOVERY_START] session=${receipt.sessionId} viewer=$receiptViewerAddress host=$sessionVaultHostAddress",
                                                        updateResult.exceptionOrNull(),
                                                    )
                                                    MppPayments
                                                        .setAuthorizedSignerForSession(
                                                            signer = signer,
                                                            appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                                                            viewerAddress = receiptViewerAddress,
                                                            hostAddress = sessionVaultHostAddress,
                                                            authorizedSignerPublicKey = signer.authorizedSignerPublicKey,
                                                        ).onSuccess { signerTxId ->
                                                            Log.e(
                                                                TAG,
                                                                "[VIEWER_VOUCHER_SET_SIGNER_RECOVERY_OK] session=${receipt.sessionId} txId=$signerTxId",
                                                            )
                                                        }.onFailure { signerErr ->
                                                            Log.e(
                                                                TAG,
                                                                "[VIEWER_VOUCHER_SET_SIGNER_RECOVERY_ERR] session=${receipt.sessionId} viewer=$receiptViewerAddress host=$sessionVaultHostAddress",
                                                                signerErr,
                                                            )
                                                        }
                                                    delay(350L)
                                                    updateResult = updateVoucherOnChain()
                                                }
                                            }

                                            updateResult
                                                .onSuccess { txId ->
                                                    Log.e(
                                                        TAG,
                                                        "✅ Viewer updateVoucher sent: txId=$txId claimed=$voucherClaimed viewer=$receiptViewerAddress host=$sessionVaultHostAddress",
                                                    )
                                                }.onFailure { err ->
                                                    val errText = err.message.orEmpty()
                                                    val duplicateVoucherUpdate =
                                                        MppPayments.isDuplicateVoucherUpdateError(errText)
                                                    if (duplicateVoucherUpdate) {
                                                        Log.e(
                                                            TAG,
                                                            "[VIEWER_VOUCHER_DUPLICATE_SKIP] claimed=$voucherClaimed viewer=$receiptViewerAddress host=$sessionVaultHostAddress reason=already_recorded_onchain",
                                                            err,
                                                        )
                                                    } else {
                                                        Log.e(
                                                            TAG,
                                                            "❌ Viewer updateVoucher failed: claimed=$voucherClaimed viewer=$receiptViewerAddress host=$sessionVaultHostAddress",
                                                            err,
                                                        )
                                                    }
                                                }

                                            val onChainDynamicData =
                                                safeApiCall("getSessionDynamicData.postUpdate") {
                                                    MppPayments.getSessionDynamicDataFromVault(
                                                        viewerAddress = receiptViewerAddress,
                                                        hostAddress = sessionVaultHostAddress,
                                                        appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                                                        authorizedSignerPublicKey = signer.authorizedSignerPublicKey,
                                                    )
                                                }
                                            val onChainLatestVoucher = onChainDynamicData?.latestVoucherAmount ?: 0L
                                            val onChainLastSettled = onChainDynamicData?.lastSettled ?: 0L
                                            val duplicateVoucherUpdate =
                                                MppPayments.isDuplicateVoucherUpdateError(
                                                    updateResult.exceptionOrNull()?.message.orEmpty(),
                                                )
                                            val caughtUp = onChainLatestVoucher >= voucherClaimed
                                            val effectiveUpdateOk = updateResult.isSuccess || (duplicateVoucherUpdate && caughtUp)
                                            val lagMicroUsdc = (voucherClaimed - onChainLatestVoucher).coerceAtLeast(0L)
                                            Log.e(
                                                TAG,
                                                "[VIEWER_VOUCHER_CATCHUP] session=${receipt.sessionId} viewer=$receiptViewerAddress localClaimedMicroUsdc=$voucherClaimed onChainLatestVoucherMicroUsdc=$onChainLatestVoucher onChainLastSettledMicroUsdc=$onChainLastSettled caughtUp=$caughtUp lagMicroUsdc=$lagMicroUsdc updateOk=$effectiveUpdateOk duplicateSkip=$duplicateVoucherUpdate",
                                            )

                                            val progressSnapshot =
                                                safeApiCall("getSessionProgressSnapshot.postUpdate") {
                                                    MppPayments.getSessionProgressSnapshotFromVault(
                                                        viewerAddress = receiptViewerAddress,
                                                        hostAddress = sessionVaultHostAddress,
                                                        appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                                                        authorizedSignerPublicKey = signer.authorizedSignerPublicKey,
                                                    )
                                                }
                                            val voucherJson =
                                                MppPayments.createVoucherJson(
                                                    sessionId = receipt.sessionId,
                                                    viewerAddress = receiptViewerAddress,
                                                    viewerPublicKey = signer.authorizedSignerPublicKey,
                                                    creatorAddress = receipt.payTo,
                                                    blocksConsumed = blocksConsumed,
                                                    totalAmountUsed = voucherClaimed,
                                                    remainingMicroUsdc = progressSnapshot?.progressBalanceMicroUsdc ?: 0L,
                                                    signatureBase64 = MppPayments.serializeVoucherSignature(voucherSignature),
                                                )
                                            val signatureBase64 = MppPayments.serializeVoucherSignature(voucherSignature)
                                            Log.e(
                                                TAG,
                                                "[SESSION_VAULT_VOUCHER_SEND] session=${receipt.sessionId} segment=${receipt.segmentIndex} claimedAmountMicroUsdc=$voucherClaimed viewer=$receiptViewerAddress host=$sessionVaultHostAddress sigLen=${voucherSignature.size}",
                                            )
                                            Log.e(
                                                TAG,
                                                "🎟️ Viewer generated voucher update: $voucherJson sig=$signatureBase64",
                                            )
                                            service.send(voucherJson)
                                        }
                                    }
                                }

                                val progressSnapshot =
                                    safeApiCall("getSessionProgressSnapshot.onReceipt") {
                                        MppPayments.getSessionProgressSnapshotFromVault(
                                            viewerAddress = receiptViewerAddress,
                                            hostAddress = sessionVaultHostAddress,
                                            appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                                            authorizedSignerPublicKey = signer.authorizedSignerPublicKey,
                                        )
                                    }
                                val onChainRemaining = progressSnapshot?.remainingSettledMicroUsdc ?: 0L
                                val progressBalance = progressSnapshot?.progressBalanceMicroUsdc ?: 0L
                                Log.e(
                                    TAG,
                                    "[VIEWER_SESSION_VAULT_FETCH_ON_RECEIPT] viewer=$receiptViewerAddress segment=${receipt.segmentIndex} remaining=$onChainRemaining progress=$progressBalance",
                                )
                                params.setViewerSessionVaultProgress(onChainRemaining, progressBalance)
                            }
                        }
                        viewer.rtcClient.onStreamGated = { reason ->
                            Log.w(TAG, "[VIEWER_STREAM_GATED] viewer=$viewerAddress host=$sessionVaultHostAddress reason=$reason")
                            params.scope.launch {
                                runCatching {
                                    val approval =
                                        params.requestMppConsent(
                                            ConsentTerms(
                                                gatingMode = GatingMode.PARTIAL_TIME,
                                                amount = MppPayments.voucherSettleWindowMicroUsdc().toString(),
                                                asset = "USDC",
                                                network = mppNetwork,
                                                payTo = sessionVaultHostAddress,
                                                segmentDuration = 3,
                                            ),
                                        )

                                    if (!approval.approved) {
                                        Log.w(
                                            TAG,
                                            "[VIEWER_STREAM_GATED_CONSENT_REJECTED] viewer=$viewerAddress host=$sessionVaultHostAddress",
                                        )
                                        return@runCatching
                                    }

                                    val depositMicroUsdc =
                                        approval.budgetCap
                                            ?.amount
                                            ?.toLongOrNull()
                                            ?.takeIf { it > 0L }
                                            ?: 1_000_000L

                                    Log.e(
                                        TAG,
                                        "[VIEWER_STREAM_GATED_TOPUP_START] viewer=$viewerAddress host=$sessionVaultHostAddress amountMicroUsdc=$depositMicroUsdc",
                                    )

                                    // Check whether the session vault already exists so we can
                                    // choose between topUp (existing session) vs openAndDeposit (new session).
                                    val existingSessionData =
                                        safeApiCall("getSessionDynamicData.streamGated") {
                                            MppPayments.getSessionDynamicDataFromVault(
                                                viewerAddress = viewerAddress,
                                                hostAddress = sessionVaultHostAddress,
                                                appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                                                authorizedSignerPublicKey = signer.authorizedSignerPublicKey,
                                            )
                                        }

                                    if (existingSessionData != null) {
                                        // Session vault exists — just top it up with more funds.
                                        MppPayments
                                            .topUpSessionVault(
                                                signer = signer,
                                                additionalDepositMicroUsdc = depositMicroUsdc,
                                            ).onSuccess { txId ->
                                                Log.e(
                                                    TAG,
                                                    "[VIEWER_STREAM_GATED_TOPUP_OK] txId=$txId viewer=$viewerAddress host=$sessionVaultHostAddress amountMicroUsdc=$depositMicroUsdc",
                                                )
                                                val onChainRemaining =
                                                    getRemainingSessionVaultBalanceUseCase(
                                                        GetRemainingSessionVaultBalanceUseCase.Params(
                                                            viewerAddress = viewerAddress,
                                                            hostAddress = sessionVaultHostAddress,
                                                            appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                                                            authorizedSignerPublicKey = signer.authorizedSignerPublicKey,
                                                        ),
                                                    ).getOrDefault(0L)
                                                params.setViewerSessionVaultProgress(onChainRemaining, onChainRemaining)
                                                startViewerOnChainRefresh(
                                                    scope = params.scope,
                                                    viewerAddress = viewerAddress,
                                                    hostAddress = sessionVaultHostAddress,
                                                    authorizedSignerPublicKey = signer.authorizedSignerPublicKey,
                                                    setViewerSessionVaultProgress = params.setViewerSessionVaultProgress,
                                                )
                                                // Extend the viewer's budget cap so the next payment
                                                // request doesn't immediately hit the exhausted limit,
                                                // then tell the server to re-issue the segment request.
                                                liquidStreamViewer?.rtcClient?.extendBudget(
                                                    additionalMicroUsdc = depositMicroUsdc,
                                                    asset = "USDC",
                                                )
                                                liquidStreamViewer?.rtcClient?.notifyVaultFunded(
                                                    sessionId = viewerVoucherSessionId ?: "",
                                                )
                                            }.onFailure {
                                                Log.e(
                                                    TAG,
                                                    "[VIEWER_STREAM_GATED_TOPUP_ERR] viewer=$viewerAddress host=$sessionVaultHostAddress amountMicroUsdc=$depositMicroUsdc",
                                                    it,
                                                )
                                            }
                                    } else {
                                        // No session vault yet — open a new one and deposit.
                                        MppPayments
                                            .openSessionAndDeposit(
                                                signer = signer,
                                                viewerAddress = viewerAddress,
                                                creatorAddress = sessionVaultHostAddress,
                                                depositAmountMicroUsdc = depositMicroUsdc,
                                            ).onSuccess { txId ->
                                                Log.e(
                                                    TAG,
                                                    "[VIEWER_STREAM_GATED_OPEN_DEPOSIT_OK] txId=$txId viewer=$viewerAddress host=$sessionVaultHostAddress amountMicroUsdc=$depositMicroUsdc",
                                                )
                                                MppPayments
                                                    .setAuthorizedSignerForSession(
                                                        signer = signer,
                                                        appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                                                        viewerAddress = viewerAddress,
                                                        hostAddress = sessionVaultHostAddress,
                                                        authorizedSignerPublicKey = signer.authorizedSignerPublicKey,
                                                    ).onFailure {
                                                        Log.e(
                                                            TAG,
                                                            "[VIEWER_STREAM_GATED_SET_SIGNER_ERR] viewer=$viewerAddress host=$sessionVaultHostAddress",
                                                            it,
                                                        )
                                                    }
                                                val onChainRemaining =
                                                    getRemainingSessionVaultBalanceUseCase(
                                                        GetRemainingSessionVaultBalanceUseCase.Params(
                                                            viewerAddress = viewerAddress,
                                                            hostAddress = sessionVaultHostAddress,
                                                            appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                                                            authorizedSignerPublicKey = signer.authorizedSignerPublicKey,
                                                        ),
                                                    ).getOrDefault(0L)
                                                params.setViewerSessionVaultProgress(onChainRemaining, onChainRemaining)
                                                startViewerOnChainRefresh(
                                                    scope = params.scope,
                                                    viewerAddress = viewerAddress,
                                                    hostAddress = sessionVaultHostAddress,
                                                    authorizedSignerPublicKey = signer.authorizedSignerPublicKey,
                                                    setViewerSessionVaultProgress = params.setViewerSessionVaultProgress,
                                                )
                                                // Extend the viewer's budget cap so the next payment
                                                // request doesn't immediately hit the exhausted limit,
                                                // then tell the server to re-issue the segment request.
                                                liquidStreamViewer?.rtcClient?.extendBudget(
                                                    additionalMicroUsdc = depositMicroUsdc,
                                                    asset = "USDC",
                                                )
                                                liquidStreamViewer?.rtcClient?.notifyVaultFunded(
                                                    sessionId = viewerVoucherSessionId ?: "",
                                                )
                                            }.onFailure {
                                                Log.e(
                                                    TAG,
                                                    "[VIEWER_STREAM_GATED_OPEN_DEPOSIT_ERR] viewer=$viewerAddress host=$sessionVaultHostAddress amountMicroUsdc=$depositMicroUsdc",
                                                    it,
                                                )
                                            }
                                    }
                                }.onFailure {
                                    Log.e(
                                        TAG,
                                        "[VIEWER_STREAM_GATED_CONSENT_ERR] viewer=$viewerAddress host=$sessionVaultHostAddress",
                                        it,
                                    )
                                }
                            }
                        }
                        Log.d(TAG, "[VIEWER_MPP_START] viewer=$viewerAddress host=$sessionVaultHostAddress network=$mppNetwork")
                        viewer.start()
                        Log.d(TAG, "[VIEWER_MPP_STARTED] viewer=$viewerAddress host=$sessionVaultHostAddress")
                    }
            } catch (_: CancellationException) {
                Log.w(TAG, "[VIEWER_MPP_SETUP_CANCELLED] viewer=$viewerAddress host=$sessionVaultHostAddress")
            } catch (e: Exception) {
                Log.e(TAG, "[VIEWER_MPP_SETUP_FAILED] viewer=$viewerAddress host=$sessionVaultHostAddress", e)
            }
        }
    }

    fun startViewerOnChainRefresh(
        scope: CoroutineScope,
        viewerAddress: String,
        hostAddress: String?,
        authorizedSignerPublicKey: ByteArray? = null,
        setViewerSessionVaultProgress: (remainingBalanceMicroUsdc: Long, progressBalanceMicroUsdc: Long) -> Unit,
    ) {
        if (viewerAddress.isBlank()) {
            Log.w(TAG, "[VIEWER_SESSION_VAULT_REFRESH_SKIP] reason=blank_viewer")
            return
        }
        val sessionVaultHostAddress = hostAddress?.takeIf { it.isNotBlank() } ?: return
        Log.d(TAG, "[VIEWER_SESSION_VAULT_REFRESH_START] viewer=$viewerAddress host=$sessionVaultHostAddress")
        stopViewerOnChainRefresh()
        viewerOnChainRefreshJob =
            scope.launch {
                while (isActive) {
                    runCatching {
                        getRemainingSessionVaultBalanceUseCase(
                            GetRemainingSessionVaultBalanceUseCase.Params(
                                viewerAddress = viewerAddress,
                                hostAddress = sessionVaultHostAddress,
                                appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                                authorizedSignerPublicKey = authorizedSignerPublicKey ?: viewerAuthorizedSignerPublicKey,
                            ),
                        ).getOrThrow()
                    }.onSuccess { remaining ->
                        Log.d(
                            TAG,
                            "[VIEWER_SESSION_VAULT_REFRESH_TICK] viewer=$viewerAddress host=$sessionVaultHostAddress remaining=$remaining",
                        )
                        setViewerSessionVaultProgress(remaining, remaining)
                        // When the on-chain balance reaches 0, collapse the local budget cap
                        // to the current spend so the very next segment payment immediately
                        // triggers onBudgetExceeded → onStreamGated → topUp popup.
                        // Without this, the viewer keeps auto-paying additional segments until
                        // the locally-tracked spend naturally reaches the initial budget cap,
                        // delaying the popup by up to N more segments.
                        if (remaining == 0L) {
                            val viewer = liquidStreamViewer
                            if (viewer != null) {
                                Log.e(
                                    TAG,
                                    "[VIEWER_SESSION_VAULT_BALANCE_DEPLETED] viewer=$viewerAddress host=$sessionVaultHostAddress collapsing_budget_cap",
                                )
                                viewer.rtcClient.extendBudget(
                                    additionalMicroUsdc = 0L,
                                    asset = "USDC",
                                )
                            }
                        }
                    }.onFailure {
                        Log.e(
                            TAG,
                            "[VIEWER_SESSION_VAULT_REFRESH_ERR] viewer=$viewerAddress host=$sessionVaultHostAddress",
                            it,
                        )
                    }
                    delay(1000L)
                }
            }
    }

    fun stop() {
        stopViewerOnChainRefresh()
        liquidStreamViewer?.terminate()
        liquidStreamViewer = null
        viewerAuthorizedSignerPublicKey = null
        viewerVoucherSessionId = null
        viewerVoucherBlocksConsumed = 0
        viewerVoucherClaimedMicroUsdc = 0L
    }

    private fun stopViewerOnChainRefresh() {
        viewerOnChainRefreshJob?.cancel()
        viewerOnChainRefreshJob = null
    }

    private suspend fun awaitPaymentDataChannel(service: SignalService): DataChannel? {
        service.getDataChannel(PAYMENT_CHANNEL_LABEL)?.let { return it }
        repeat(20) {
            service.getDataChannel(PAYMENT_CHANNEL_LABEL)?.let { channel -> return channel }
            delay(100L)
        }
        service.createDataChannel(PAYMENT_CHANNEL_LABEL)?.let { return it }

        return suspendCancellableCoroutine { continuation ->
            val handler = Handler(Looper.getMainLooper())
            val poll =
                object : Runnable {
                    override fun run() {
                        val channel = service.getDataChannel(PAYMENT_CHANNEL_LABEL)
                        if (!continuation.isActive) return
                        if (channel != null) {
                            continuation.resume(channel)
                        } else {
                            handler.postDelayed(this, 100L)
                        }
                    }
                }
            handler.postDelayed(poll, 100L)
            continuation.invokeOnCancellation { handler.removeCallbacks(poll) }
        }
    }

    private suspend fun <T> safeApiCall(
        apiName: String,
        block: suspend () -> T,
    ): T? =
        try {
            block()
        } catch (t: Throwable) {
            Log.e(TAG, "[VIEWER_API_ERR] api=$apiName", t)
            null
        }
}
