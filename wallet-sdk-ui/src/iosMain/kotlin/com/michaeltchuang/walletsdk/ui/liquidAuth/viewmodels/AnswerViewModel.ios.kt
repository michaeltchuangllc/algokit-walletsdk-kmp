package com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels

import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAccountAlgoBalance
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAlgo25SecretKey
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetFalcon24SecretKey
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetFalcon25PrivateKey
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetHdSeed
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetLocalAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetLocalAccounts
import com.michaeltchuang.walletsdk.core.network.domain.usecase.GetCurrentNetworkUseCase
import com.michaeltchuang.walletsdk.core.network.usecase.GetCurrentBlockUseCase
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ChatMessage
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetRemainingSessionVaultBalanceUseCase
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetSessionVaultConfigUseCase
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.MppWalletSignerUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.service.LiquidAuthPlatformServices
import com.michaeltchuang.walletsdk.ui.liquidStream.domain.manager.MppPaymentViewerManager
import com.michaeltchuang.walletsdk.ui.liquidStream.domain.usecases.SetupMppPaymentViewerUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope

actual open class AnswerViewModel actual constructor(
    getCurrentBlockUseCase: GetCurrentBlockUseCase,
    getAccountAlgoBalance: GetAccountAlgoBalance,
    getLocalAccount: GetLocalAccount,
    getLocalAccounts: GetLocalAccounts,
    getAlgo25SecretKey: GetAlgo25SecretKey,
    getFalcon24SecretKey: GetFalcon24SecretKey,
    getFalcon25PrivateKey: GetFalcon25PrivateKey,
    getSeed: GetHdSeed,
    getCurrentNetworkUseCase: GetCurrentNetworkUseCase,
    getRemainingSessionVaultBalanceUseCase: GetRemainingSessionVaultBalanceUseCase,
    getSessionVaultConfigUseCase: GetSessionVaultConfigUseCase,
    setupMppPaymentViewerUseCase: SetupMppPaymentViewerUseCase,
    mppPaymentViewerManager: MppPaymentViewerManager,
    mppWalletSignerUseCase: MppWalletSignerUseCase,
) : CommonAnswerViewModel(
        getCurrentBlockUseCase = getCurrentBlockUseCase,
        getAccountAlgoBalance = getAccountAlgoBalance,
        getLocalAccount = getLocalAccount,
        getLocalAccounts = getLocalAccounts,
        getAlgo25SecretKey = getAlgo25SecretKey,
        getFalcon24SecretKey = getFalcon24SecretKey,
        getFalcon25PrivateKey = getFalcon25PrivateKey,
        getSeed = getSeed,
        getCurrentNetworkUseCase = getCurrentNetworkUseCase,
        getRemainingSessionVaultBalanceUseCase = getRemainingSessionVaultBalanceUseCase,
        getSessionVaultConfigUseCase = getSessionVaultConfigUseCase,
        setupMppPaymentViewerUseCase = setupMppPaymentViewerUseCase,
        mppPaymentViewerManager = mppPaymentViewerManager,
        mppWalletSignerUseCase = mppWalletSignerUseCase,
    ) {
    val platformServices = LiquidAuthPlatformServices()

    override fun doSendChatMessage(message: ChatMessage) {
        mppPaymentViewerManager.sendChatMessage(message)
    }

    override fun onChatMessageReceived(message: ChatMessage) {
        super.onChatMessageReceived(message)
    }

    suspend fun setupViewerPaymentRail(
        viewerAddress: String,
        hostAddress: String = "",
        scope: CoroutineScope,
    ): Boolean =
        try {
            require(viewerAddress.isNotBlank()) { "Select an account for streaming payments." }
            val mppNetwork = resolveMppClientNetwork(viewerAddress)
            val signer = buildMppWalletSigner(viewerAddress) ?: error("The selected account cannot sign streaming payments.")
            platformServices.closeViewerPaymentDataChannel()
            val dataChannel = platformServices.createViewerPaymentDataChannel()
            setupMppPaymentViewerUseCase(
                SetupMppPaymentViewerUseCase.Params(
                    dataChannel = dataChannel,
                    viewerAddress = viewerAddress,
                    hostAddress = hostAddress,
                    scope = scope,
                    signer = signer,
                    mppNetwork = mppNetwork,
                    signSolanaTransaction = signSolanaTransactionDelegate,
                    requestMppConsent = ::requestMppConsentFromUi,
                    getConnectionType = { connectionType.value },
                    onStreamRejected = ::onStreamRejected,
                    onPaymentError = ::setError,
                    setViewerPaymentProcessing = ::setViewerPaymentProcessing,
                    setViewerSessionVaultProgress = ::setViewerSessionVaultProgress,
                    onVaultSnapshot = ::setViewerVaultSnapshot,
                    signFido2Challenge = { challenge, challengeAddress ->
                        signer.takeIf { it.address == challengeAddress }?.signMessage(challenge)
                            ?: buildMppWalletSigner(challengeAddress)?.signMessage(challenge)
                            ?: signFido2Challenge(challenge, challengeAddress)
                    },
                    onChatMessageReceived = ::onChatMessageReceived,
                    getHostAddress = { this.hostAddress.value.ifBlank { hostAddress } },
                ),
            )
            true
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            onStreamRejected("payment_setup_failed", e.message ?: "Could not set up streaming payments.")
            false
        }

    fun openViewerPaymentRail() {
        platformServices.openViewerPaymentDataChannel()
    }

    fun closeViewerPaymentRail() {
        mppPaymentViewerManager.stop()
        platformServices.closeViewerPaymentDataChannel()
    }

    fun handlePlatformPaymentMessage(message: String): Boolean = platformServices.notifyViewerPaymentMessage(message)
}
