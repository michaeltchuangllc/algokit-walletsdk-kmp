package com.michaeltchuang.walletsdk.ui.liquidStream.domain.usecases

import com.michaeltchuang.walletsdk.core.network.model.AlgorandNetwork
import com.michaeltchuang.walletsdk.core.railmpp.MppNetworks
import com.michaeltchuang.walletsdk.core.railmpp.core.RtcDataChannel
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ChatMessage
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ConsentApproval
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ConsentTerms
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetSessionVaultConfigUseCase
import com.michaeltchuang.walletsdk.core.railmpp.smartcontract.EscrowSessionVaultHybridManagerClient
import com.michaeltchuang.walletsdk.ui.liquidStream.domain.manager.MppPaymentViewerManager
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope

/**
 * Wires up a viewer's payment rail once the native WebRTC payment data channel and signer are
 * ready. Platform-specific work (waiting for the data channel to become available, building the
 * wallet signer, resolving the active network) happens *before* this use case is invoked — see
 * `AnswerViewModel.android.kt` (`setupMppPaymentViewer`) and `AnswerViewModel.ios.kt`
 * (`setupViewerPaymentRail`) — so this class only needs to hold platform-agnostic logic.
 */
class SetupMppPaymentViewerUseCase(
    private val viewerManager: MppPaymentViewerManager,
    private val getSessionVaultConfigUseCase: GetSessionVaultConfigUseCase,
) {
    companion object {
        private const val TAG = "SetupMppPaymentViewer"
    }

    data class Params(
        val dataChannel: RtcDataChannel,
        val viewerAddress: String,
        val hostAddress: String = "",
        val scope: CoroutineScope,
        val signer: MppWalletSigner,
        val mppNetwork: String,
        val requestMppConsent: suspend (ConsentTerms) -> ConsentApproval,
        val setViewerSessionVaultProgress: (remainingBalanceMicroUsdc: Long, progressBalanceMicroUsdc: Long) -> Unit,
        val signFido2Challenge: suspend (challenge: ByteArray, address: String) -> ByteArray?,
        val onChatMessageReceived: (ChatMessage) -> Unit = {},
    )

    operator fun invoke(params: Params) {
        val viewerAddress =
            params.viewerAddress.takeIf { it.isNotBlank() }
                ?: run {
                    Napier.w("[VIEWER_MPP_SETUP_SKIP] reason=blank_viewer host=${params.hostAddress}", tag = TAG)
                    return
                }
        val sessionVaultNetwork = params.mppNetwork.toAlgorandNetwork()
        EscrowSessionVaultHybridManagerClient.configureForNetwork(sessionVaultNetwork)
        val sessionVaultConfig = getSessionVaultConfigUseCase(sessionVaultNetwork)
        viewerManager.start(
            MppPaymentViewerManager.StartParams(
                dataChannel = params.dataChannel,
                viewerAddress = viewerAddress,
                scope = params.scope,
                signer = params.signer,
                mppNetwork = params.mppNetwork,
                sessionVaultAppId = sessionVaultConfig.appId,
                requestMppConsent = params.requestMppConsent,
                setViewerSessionVaultProgress = params.setViewerSessionVaultProgress,
                signFido2Challenge = params.signFido2Challenge,
                onChatMessageReceived = params.onChatMessageReceived,
            ),
        )
    }

    private fun String.toAlgorandNetwork(): AlgorandNetwork =
        when {
            this == MppNetworks.ALGORAND_MAINNET || contains("mainnet", ignoreCase = true) -> AlgorandNetwork.MAINNET
            this == MppNetworks.ALGORAND_FUTURENET ||
                contains(
                    "futurenet",
                    ignoreCase = true,
                ) ||
                contains("fnet", ignoreCase = true) -> AlgorandNetwork.FUTURENET
            else -> AlgorandNetwork.TESTNET
        }
}
