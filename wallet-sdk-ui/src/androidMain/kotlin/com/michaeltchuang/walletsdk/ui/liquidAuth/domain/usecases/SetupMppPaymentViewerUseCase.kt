package com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases

import android.os.Handler
import android.util.Log
import com.michaeltchuang.walletsdk.core.deeplink.utils.AssetConstants.USDC_TESTNET_ID
import com.michaeltchuang.walletsdk.core.liquidAuth.auth.connect.SignalService
import com.michaeltchuang.walletsdk.core.railmpp.LiquidStreamViewer
import com.michaeltchuang.walletsdk.core.railmpp.MppClientConfig
import com.michaeltchuang.walletsdk.core.railmpp.MppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.core.BudgetCap
import com.michaeltchuang.walletsdk.core.railmpp.core.ClientConfig
import com.michaeltchuang.walletsdk.core.railmpp.core.ConsentApproval
import com.michaeltchuang.walletsdk.core.railmpp.core.ConsentHandler
import com.michaeltchuang.walletsdk.core.railmpp.core.ConsentTerms
import com.michaeltchuang.walletsdk.core.railmpp.core.GatingMode
import com.michaeltchuang.walletsdk.core.railmpp.core.PAYMENT_CHANNEL_LABEL
import com.michaeltchuang.walletsdk.core.railmpp.utils.MppPayments
import com.michaeltchuang.walletsdk.core.railmpp.utils.RailMppConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.DataChannel
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume

class SetupMppPaymentViewerUseCase {
    companion object {
        private const val TAG = "SetupMppPaymentViewer"
    }

    data class Params(
        val signalService: SignalService?,
        val viewerAddress: String?,
        val hostAddress: String?,
        val scope: CoroutineScope,
        val buildMppWalletSigner: suspend (String) -> MppWalletSigner?,
        val resolveMppClientNetwork: suspend (String) -> String,
        val requestMppConsent: suspend (ConsentTerms) -> ConsentApproval,
        val setViewerSessionVaultBalance: (balanceMicroUsdc: Long, resetVoucherUsage: Boolean) -> Unit,
        val applyViewerSegmentDebit: (Long) -> Unit,
        val viewerSessionVaultMicroUsdc: () -> Long,
        val signFido2Challenge: suspend (challenge: ByteArray, address: String) -> ByteArray?,
    )

    private var liquidStreamViewer: LiquidStreamViewer? = null
    private var viewerOnChainRefreshJob: Job? = null

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
        val peerConnection =
            service.peerConnection
                ?: run {
                    Log.e(TAG, "[VIEWER_MPP_SETUP_SKIP] reason=missing_peer_connection")
                    return
                }
        val accountAddress =
            params.viewerAddress?.takeIf { it.isNotBlank() }
                ?: run {
                    Log.w(TAG, "[VIEWER_MPP_SETUP_SKIP] reason=blank_viewer host=${params.hostAddress.orEmpty()}")
                    return
                }
        val sessionVaultHostAddress =
            params.hostAddress?.takeIf { it.isNotBlank() }
                ?: run {
                    Log.w(TAG, "[VIEWER_MPP_SETUP_SKIP] reason=blank_host viewer=$accountAddress")
                    return
                }

        params.scope.launch {
            try {
                Log.d(TAG, "[VIEWER_MPP_PAYMENT_CHANNEL_WAIT] viewer=$accountAddress host=$sessionVaultHostAddress")
                val paymentChannel =
                    awaitPaymentDataChannel(service)
                        ?: run {
                            Log.e(
                                TAG,
                                "[VIEWER_MPP_SETUP_SKIP] reason=missing_payment_channel viewer=$accountAddress host=$sessionVaultHostAddress",
                            )
                            return@launch
                        }
                Log.d(
                    TAG,
                    "[VIEWER_MPP_PAYMENT_CHANNEL_READY] viewer=$accountAddress host=$sessionVaultHostAddress label=${paymentChannel.label()} state=${paymentChannel.state()}",
                )
                val signer = params.buildMppWalletSigner(accountAddress)
                if (signer == null) {
                    Log.e(
                        TAG,
                        "[VIEWER_MPP_SETUP_SKIP] reason=missing_signer viewer=$accountAddress host=$sessionVaultHostAddress",
                    )
                    return@launch
                }
                Log.d(TAG, "[VIEWER_MPP_SIGNER_READY] signerAddress=${signer.address} viewer=$accountAddress")

                Log.d(TAG, "[VIEWER_MPP_CLEANUP_PREVIOUS] viewer=$accountAddress")
                stopViewerOnChainRefresh()
                liquidStreamViewer?.terminate()

                val mppNetwork = params.resolveMppClientNetwork(accountAddress)
                Log.d(
                    TAG,
                    "[VIEWER_MPP_CREATE_VIEWER] viewer=$accountAddress host=$sessionVaultHostAddress network=$mppNetwork",
                )
                liquidStreamViewer =
                    LiquidStreamViewer(
                        peerConnection = peerConnection,
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
                                        "[VIEWER_MPP_CONSENT_REQUEST] viewer=$accountAddress host=$sessionVaultHostAddress amount=${terms.amount} asset=${terms.asset} network=${terms.network} gating=${terms.gatingMode}",
                                    )
                                    Log.d(
                                        TAG,
                                        "[VIEWER_SESSION_VAULT_FETCH_BEFORE_CONSENT] viewer=$accountAddress host=$sessionVaultHostAddress",
                                    )
                                    val existingOnChainBalance =
                                        MppPayments.getRemainingBalanceFromSessionVault(
                                            viewerAddress = accountAddress,
                                            hostAddress = sessionVaultHostAddress,
                                            appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                                        )

                                    if (existingOnChainBalance != null && existingOnChainBalance > 0L) {
                                        params.setViewerSessionVaultBalance(existingOnChainBalance, false)
                                        Log.d(
                                            TAG,
                                            "[VIEWER_SESSION_VAULT_FUNDED] balanceMicroUsdc=$existingOnChainBalance balanceUsdc=${existingOnChainBalance / 1_000_000.0} action=skip_payment_modal",
                                        )
                                        startViewerOnChainRefresh(
                                            scope = params.scope,
                                            viewerAddress = accountAddress,
                                            hostAddress = sessionVaultHostAddress,
                                            setViewerSessionVaultBalance = params.setViewerSessionVaultBalance,
                                        )
                                        return ConsentApproval(
                                            approved = true,
                                            autoPaySegments = true,
                                            budgetCap =
                                                BudgetCap(
                                                    amount = existingOnChainBalance.toString(),
                                                    asset = USDC_TESTNET_ID.toString(),
                                                ),
                                        )
                                    }

                                    val approval = params.requestMppConsent(terms)
                                    Log.d(
                                        TAG,
                                        "[VIEWER_MPP_CONSENT_RESULT] viewer=$accountAddress host=$sessionVaultHostAddress approved=${approval.approved} autoPay=${approval.autoPaySegments} budget=${approval.budgetCap?.amount}",
                                    )
                                    if (!approval.approved) {
                                        Log.w(
                                            TAG,
                                            "[VIEWER_MPP_CONSENT_REJECTED] viewer=$accountAddress host=$sessionVaultHostAddress",
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
                                        "[VIEWER_SESSION_VAULT_DEPOSIT_START] viewer=$accountAddress host=$sessionVaultHostAddress amountMicroUsdc=$depositMicroUsdc",
                                    )
                                    MppPayments
                                        .openSessionAndDeposit(
                                            signer = signer,
                                            viewerAddress = accountAddress,
                                            creatorAddress = sessionVaultHostAddress,
                                            depositAmountMicroUsdc = depositMicroUsdc,
                                        ).onSuccess { txId ->
                                            Log.d(TAG, "✅ Session Vault openSession+deposit txId=$txId")
                                            val onChainRemaining =
                                                MppPayments.getRemainingBalanceFromSessionVault(
                                                    viewerAddress = accountAddress,
                                                    hostAddress = sessionVaultHostAddress,
                                                    appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                                                )
                                            Log.d(
                                                TAG,
                                                "[VIEWER_SESSION_VAULT_FETCH_AFTER_DEPOSIT] viewer=$accountAddress remaining=$onChainRemaining",
                                            )
                                            if (onChainRemaining != null) {
                                                params.setViewerSessionVaultBalance(onChainRemaining, true)
                                            } else {
                                                Log.e(
                                                    TAG,
                                                    "[VIEWER_SESSION_VAULT_FETCH_AFTER_DEPOSIT_NULL] viewer=$accountAddress",
                                                )
                                            }
                                            startViewerOnChainRefresh(
                                                scope = params.scope,
                                                viewerAddress = accountAddress,
                                                hostAddress = sessionVaultHostAddress,
                                                setViewerSessionVaultBalance = params.setViewerSessionVaultBalance,
                                            )
                                        }.onFailure {
                                            Log.e(TAG, "❌ Session Vault openSession+deposit failed", it)
                                        }

                                    return approval
                                }
                            },
                        clientConfig = ClientConfig(autoPaySegments = false),
                    ).also { viewer ->
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
                                val receiptViewerAddress = receipt.payFrom.ifBlank { accountAddress }
                                val blocksConsumed = (receipt.segmentIndex + 1).coerceAtLeast(0)
                                val voucherClaimed = MppPayments.computeVoucherMicroUsdcUsage(blocksConsumed)

                                Log.d(
                                    TAG,
                                    "💸 MPP receipt accepted: session=${receipt.sessionId} segment=${receipt.segmentIndex} amount=${receipt.amount} asset=${receipt.asset} txId=${receipt.txId}",
                                )

                                if (
                                    MppPayments.shouldAttemptVoucherSettlement(blocksConsumed) &&
                                    receiptViewerAddress.isNotBlank()
                                ) {
                                    val voucherSignature =
                                        runCatching {
                                            val message =
                                                MppPayments.buildClaimMessage(
                                                    appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                                                    totalAmountClaimedMicroUsdc = voucherClaimed,
                                                )
                                            params.signFido2Challenge(message, accountAddress)
                                        }.getOrNull()

                                    if (voucherSignature != null && voucherSignature.isNotEmpty()) {
                                        val voucherJson =
                                            MppPayments.createVoucherJson(
                                                sessionId = receipt.sessionId,
                                                viewerAddress = receiptViewerAddress,
                                                creatorAddress = receipt.payTo,
                                                blocksConsumed = blocksConsumed,
                                                totalAmountUsed = voucherClaimed,
                                                remainingMicroUsdc = params.viewerSessionVaultMicroUsdc(),
                                            )
                                        Log.d(
                                            TAG,
                                            "🎟️ Viewer generated voucher (settlement cadence): $voucherJson sig=${
                                                MppPayments.serializeVoucherSignature(voucherSignature)
                                            }",
                                        )
                                    }
                                }

                                if (debit > 0L) {
                                    params.applyViewerSegmentDebit(debit)
                                }

                                val onChainRemaining =
                                    MppPayments.getRemainingBalanceFromSessionVault(
                                        viewerAddress = receiptViewerAddress,
                                        hostAddress = sessionVaultHostAddress,
                                        appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                                    )
                                Log.d(
                                    TAG,
                                    "[VIEWER_SESSION_VAULT_FETCH_ON_RECEIPT] viewer=$receiptViewerAddress segment=${receipt.segmentIndex} remaining=$onChainRemaining",
                                )
                                if (onChainRemaining != null) {
                                    params.setViewerSessionVaultBalance(onChainRemaining, false)
                                } else {
                                    Log.e(
                                        TAG,
                                        "[VIEWER_SESSION_VAULT_FETCH_ON_RECEIPT_NULL] viewer=$receiptViewerAddress segment=${receipt.segmentIndex}",
                                    )
                                }
                            }
                        }
                        viewer.rtcClient.onStreamGated = { reason ->
                            Log.w(TAG, "[VIEWER_STREAM_GATED] viewer=$accountAddress host=$sessionVaultHostAddress reason=$reason")
                            params.scope.launch {
                                runCatching {
                                    params.requestMppConsent(
                                        ConsentTerms(
                                            gatingMode = GatingMode.PARTIAL_TIME,
                                            amount = "100000",
                                            asset = USDC_TESTNET_ID.toString(),
                                            network = mppNetwork,
                                            segmentDuration = 3,
                                        ),
                                    )
                                }
                            }
                        }
                        Log.d(TAG, "[VIEWER_MPP_START] viewer=$accountAddress host=$sessionVaultHostAddress network=$mppNetwork")
                        viewer.start()
                        Log.d(TAG, "[VIEWER_MPP_STARTED] viewer=$accountAddress host=$sessionVaultHostAddress")
                    }
            } catch (_: CancellationException) {
                Log.w(TAG, "[VIEWER_MPP_SETUP_CANCELLED] viewer=$accountAddress host=$sessionVaultHostAddress")
            } catch (e: Exception) {
                Log.e(TAG, "[VIEWER_MPP_SETUP_FAILED] viewer=$accountAddress host=$sessionVaultHostAddress", e)
            }
        }
    }

    fun startViewerOnChainRefresh(
        scope: CoroutineScope,
        viewerAddress: String,
        hostAddress: String?,
        setViewerSessionVaultBalance: (balanceMicroUsdc: Long, resetVoucherUsage: Boolean) -> Unit,
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
                        MppPayments.getRemainingBalanceFromSessionVault(
                            viewerAddress = viewerAddress,
                            hostAddress = sessionVaultHostAddress,
                            appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                        )
                    }.onSuccess { remaining ->
                        Log.d(
                            TAG,
                            "[VIEWER_SESSION_VAULT_REFRESH_TICK] viewer=$viewerAddress host=$sessionVaultHostAddress remaining=$remaining",
                        )
                        if (remaining != null) {
                            setViewerSessionVaultBalance(remaining, false)
                        } else {
                            Log.e(
                                TAG,
                                "[VIEWER_SESSION_VAULT_REFRESH_NULL] viewer=$viewerAddress host=$sessionVaultHostAddress",
                            )
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
            val handler = Handler(android.os.Looper.getMainLooper())
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
}
