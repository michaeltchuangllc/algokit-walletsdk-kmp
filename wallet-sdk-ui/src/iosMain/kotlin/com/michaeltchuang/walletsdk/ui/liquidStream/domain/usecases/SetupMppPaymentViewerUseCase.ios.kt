package com.michaeltchuang.walletsdk.ui.liquidStream.domain.usecases

import com.michaeltchuang.walletsdk.core.network.model.AlgorandNetwork
import com.michaeltchuang.walletsdk.core.railmpp.MppNetworks
import com.michaeltchuang.walletsdk.core.railmpp.core.ConsentApproval
import com.michaeltchuang.walletsdk.core.railmpp.core.ConsentTerms
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetRemainingSessionVaultBalanceUseCase
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetSessionVaultConfigUseCase
import com.michaeltchuang.walletsdk.ui.liquidStream.IosRtcDataChannel
import com.michaeltchuang.walletsdk.ui.liquidStream.domain.manager.MppPaymentViewerManager
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope

actual class SetupMppPaymentViewerUseCase actual constructor(
    getRemainingSessionVaultBalanceUseCase: GetRemainingSessionVaultBalanceUseCase,
    private val getSessionVaultConfigUseCase: GetSessionVaultConfigUseCase,
) {
    companion object {
        private const val TAG = "SetupMppPaymentViewer"
    }

    data class Params(
        val dataChannel: IosRtcDataChannel,
        val viewerAddress: String,
        val hostAddress: String,
        val scope: CoroutineScope,
        val signer: MppWalletSigner,
        val mppNetwork: String,
        val requestMppConsent: suspend (ConsentTerms) -> ConsentApproval,
        val setViewerSessionVaultProgress: (remainingBalanceMicroUsdc: Long, progressBalanceMicroUsdc: Long) -> Unit,
        val signFido2Challenge: suspend (challenge: ByteArray, address: String) -> ByteArray?,
        val sendMessage: (String) -> Unit,
    )

    private val viewerManager = MppPaymentViewerManager(getRemainingSessionVaultBalanceUseCase)

    operator fun invoke(params: Params) {
        val viewerAddress = params.viewerAddress.takeIf { it.isNotBlank() }
            ?: run {
                Napier.w("[VIEWER_MPP_SETUP_SKIP] reason=blank_viewer host=${params.hostAddress}", tag = TAG)
                return
            }
        val sessionVaultHostAddress = params.hostAddress.takeIf { it.isNotBlank() }
            ?: run {
                Napier.w("[VIEWER_MPP_SETUP_SKIP] reason=blank_host viewer=$viewerAddress", tag = TAG)
                return
            }
        val sessionVaultConfig = getSessionVaultConfigUseCase(params.mppNetwork.toAlgorandNetwork())
        viewerManager.start(
            MppPaymentViewerManager.StartParams(
                dataChannel = params.dataChannel,
                viewerAddress = viewerAddress,
                hostAddress = sessionVaultHostAddress,
                scope = params.scope,
                signer = params.signer,
                mppNetwork = params.mppNetwork,
                sessionVaultAppId = sessionVaultConfig.appId,
                requestMppConsent = params.requestMppConsent,
                setViewerSessionVaultProgress = params.setViewerSessionVaultProgress,
                signFido2Challenge = params.signFido2Challenge,
                sendMessage = params.sendMessage,
            ),
        )
    }

    actual fun startViewerOnChainRefresh(
        scope: CoroutineScope,
        viewerAddress: String,
        hostAddress: String?,
        sessionVaultAppId: Long,
        authorizedSignerPublicKey: ByteArray?,
        setViewerSessionVaultProgress: (remainingBalanceMicroUsdc: Long, progressBalanceMicroUsdc: Long) -> Unit,
    ) {
        viewerManager.startViewerOnChainRefresh(
            scope = scope,
            viewerAddress = viewerAddress,
            hostAddress = hostAddress,
            sessionVaultAppId = sessionVaultAppId,
            authorizedSignerPublicKey = authorizedSignerPublicKey,
            setViewerSessionVaultProgress = setViewerSessionVaultProgress,
        )
    }

    actual fun stop() {
        viewerManager.stop()
    }

    private fun String.toAlgorandNetwork(): AlgorandNetwork =
        if (this == MppNetworks.ALGORAND_MAINNET || contains("mainnet", ignoreCase = true)) {
            AlgorandNetwork.MAINNET
        } else {
            AlgorandNetwork.TESTNET
        }
}
