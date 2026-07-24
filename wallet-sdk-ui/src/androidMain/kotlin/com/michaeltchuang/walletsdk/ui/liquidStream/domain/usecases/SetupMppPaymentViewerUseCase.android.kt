package com.michaeltchuang.walletsdk.ui.liquidStream.domain.usecases

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.michaeltchuang.walletsdk.core.liquidAuth.auth.connect.SignalService
import com.michaeltchuang.walletsdk.core.network.model.AlgorandNetwork
import com.michaeltchuang.walletsdk.core.railmpp.MppNetworks
import com.michaeltchuang.walletsdk.core.railmpp.core.PAYMENT_CHANNEL_LABEL
import com.michaeltchuang.walletsdk.core.railmpp.core.WebRtcDataChannel
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ConsentApproval
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ConsentTerms
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetSessionVaultConfigUseCase
import com.michaeltchuang.walletsdk.ui.liquidStream.domain.manager.MppPaymentViewerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.DataChannel
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

actual class SetupMppPaymentViewerUseCase actual constructor(
    private val viewerManager: MppPaymentViewerManager,
    private val getSessionVaultConfigUseCase: GetSessionVaultConfigUseCase,
) {
    companion object {
        private const val TAG = "SetupMppPaymentViewer"
    }

    data class Params(
        val signalService: SignalService?,
        val viewerAddress: String?,
        val scope: CoroutineScope,
        val buildMppWalletSigner: suspend (String) -> MppWalletSigner?,
        val resolveMppClientNetwork: suspend (String) -> String,
        val requestMppConsent: suspend (ConsentTerms) -> ConsentApproval,
        val setViewerSessionVaultProgress: (remainingBalanceMicroUsdc: Long, progressBalanceMicroUsdc: Long) -> Unit,
        val signFido2Challenge: suspend (challenge: ByteArray, address: String) -> ByteArray?,
    )


    operator fun invoke(params: Params) {
        Log.d(
            TAG,
            "[VIEWER_MPP_SETUP_START] viewer=${params.viewerAddress.orEmpty()} serviceReady=${params.signalService != null}",
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
                    Log.w(TAG, "[VIEWER_MPP_SETUP_SKIP] reason=blank_viewer")
                    return
                }

        params.scope.launch {
            try {
                Log.d(TAG, "[VIEWER_MPP_PAYMENT_CHANNEL_WAIT] viewer=$viewerAddress")
                val paymentChannel =
                    awaitPaymentDataChannel(service)
                        ?: run {
                            Log.e(
                                TAG,
                                "[VIEWER_MPP_SETUP_SKIP] reason=missing_payment_channel viewer=$viewerAddress",
                            )
                            return@launch
                        }
                Log.d(
                    TAG,
                    "[VIEWER_MPP_PAYMENT_CHANNEL_READY] viewer=$viewerAddress label=${paymentChannel.label()} state=${paymentChannel.state()}",
                )

                val signer =
                    params.buildMppWalletSigner(viewerAddress)
                        ?: run {
                            Log.e(
                                TAG,
                                "[VIEWER_MPP_SETUP_SKIP] reason=missing_signer viewer=$viewerAddress",
                            )
                            return@launch
                        }
                val mppNetwork = params.resolveMppClientNetwork(viewerAddress)
                val sessionVaultNetwork = mppNetwork.toAlgorandNetwork()
                val sessionVaultConfig = getSessionVaultConfigUseCase(sessionVaultNetwork)

                viewerManager.start(
                    MppPaymentViewerManager.StartParams(
                        dataChannel = WebRtcDataChannel(paymentChannel),
                        viewerAddress = viewerAddress,
                        scope = params.scope,
                        signer = signer,
                        mppNetwork = mppNetwork,
                        sessionVaultAppId = sessionVaultConfig.appId,
                        requestMppConsent = params.requestMppConsent,
                        setViewerSessionVaultProgress = params.setViewerSessionVaultProgress,
                        signFido2Challenge = params.signFido2Challenge,
                    ),
                )
            } catch (_: CancellationException) {
                Log.w(TAG, "[VIEWER_MPP_SETUP_CANCELLED] viewer=$viewerAddress")
            } catch (e: Exception) {
                Log.e(TAG, "[VIEWER_MPP_SETUP_FAILED] viewer=$viewerAddress", e)
            }
        }
    }


    private suspend fun awaitPaymentDataChannel(service: SignalService): DataChannel? {
        service.getDataChannel(PAYMENT_CHANNEL_LABEL)?.let { return it }
        repeat(20) {
            service.getDataChannel(PAYMENT_CHANNEL_LABEL)?.let { channel -> return channel }
            delay(100L.milliseconds)
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

    private fun String.toAlgorandNetwork(): AlgorandNetwork =
        if (this == MppNetworks.ALGORAND_MAINNET || contains("mainnet", ignoreCase = true)) {
            AlgorandNetwork.MAINNET
        } else {
            AlgorandNetwork.TESTNET
        }
}
