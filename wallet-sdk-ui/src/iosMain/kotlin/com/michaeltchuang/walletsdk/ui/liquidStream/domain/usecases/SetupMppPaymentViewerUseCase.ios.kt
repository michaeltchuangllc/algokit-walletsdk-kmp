package com.michaeltchuang.walletsdk.ui.liquidStream.domain.usecases

import com.michaeltchuang.walletsdk.core.network.model.AlgorandNetwork
import com.michaeltchuang.walletsdk.core.railmpp.MppNetworks
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ChatMessage
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ConsentApproval
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ConsentTerms
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetSessionVaultConfigUseCase
import com.michaeltchuang.walletsdk.ui.liquidStream.domain.transport.CallbackRtcDataChannel
import com.michaeltchuang.walletsdk.ui.liquidStream.domain.manager.MppPaymentViewerManager
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope

actual class SetupMppPaymentViewerUseCase actual constructor(
    private val viewerManager: MppPaymentViewerManager,
    private val getSessionVaultConfigUseCase: GetSessionVaultConfigUseCase,
) {
    companion object {
        private const val TAG = "SetupMppPaymentViewer"
    }

    data class Params(
        val dataChannel: CallbackRtcDataChannel,
        val viewerAddress: String,
        val hostAddress: String,
        val scope: CoroutineScope,
        val signer: MppWalletSigner,
        val mppNetwork: String,
        val requestMppConsent: suspend (ConsentTerms) -> ConsentApproval,
        val setViewerSessionVaultProgress: (remainingBalanceMicroUsdc: Long, progressBalanceMicroUsdc: Long) -> Unit,
        val signFido2Challenge: suspend (challenge: ByteArray, address: String) -> ByteArray?,
        val onChatMessageReceived: (ChatMessage) -> Unit = {},
    )

    operator fun invoke(params: Params) {
        val viewerAddress = params.viewerAddress.takeIf { it.isNotBlank() }
            ?: run {
                Napier.w("[VIEWER_MPP_SETUP_SKIP] reason=blank_viewer host=${params.hostAddress}", tag = TAG)
                return
            }
        val sessionVaultConfig = getSessionVaultConfigUseCase(params.mppNetwork.toAlgorandNetwork())
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
        if (this == MppNetworks.ALGORAND_MAINNET || contains("mainnet", ignoreCase = true)) {
            AlgorandNetwork.MAINNET
        } else {
            AlgorandNetwork.TESTNET
        }
}
