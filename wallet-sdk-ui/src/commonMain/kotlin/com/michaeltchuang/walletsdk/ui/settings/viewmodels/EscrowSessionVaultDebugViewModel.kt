package com.michaeltchuang.walletsdk.ui.settings.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetLocalAccounts
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateViewModel
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.DebugAddressSelections
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.DebugAddressSelectionsUseCase
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetSessionVaultContextUseCase
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.MppWalletSignerUseCase
import com.michaeltchuang.walletsdk.core.railmpp.smartcontract.EscrowSessionVaultHybridManagerClient
import com.michaeltchuang.walletsdk.core.railmpp.utils.MppPayments
import com.michaeltchuang.walletsdk.core.railmpp.utils.PaymentError
import com.michaeltchuang.walletsdk.ui.settings.domain.DebugAddressHolder
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EscrowSessionVaultDebugViewModel(
    private val stateDelegate: StateDelegate<ViewState>,
    private val eventDelegate: EventDelegate<ViewEvent>,
    private val mppWalletSignerUseCase: MppWalletSignerUseCase,
    private val getLocalAccounts: GetLocalAccounts,
    private val getSessionVaultContextUseCase: GetSessionVaultContextUseCase,
    private val debugAddressSelectionsUseCase: DebugAddressSelectionsUseCase,
) : ViewModel(),
    StateViewModel<EscrowSessionVaultDebugViewModel.ViewState> by stateDelegate,
    EventViewModel<EscrowSessionVaultDebugViewModel.ViewEvent> by eventDelegate {
    companion object Companion {
        private const val TAG = "EscrowSessionVaultDebugViewModel"
        private const val MICRO_USDC_MULTIPLIER = 1_000_000L
    }

    data class PendingVoucher(
        val cumulativeAmountMicroUsdc: Long,
        val signature: ByteArray,
        val authorizedSignerPublicKey: ByteArray,
    )

    init {
        stateDelegate.setDefaultState(ViewState.Content())
        loadAccountAddresses()
    }

    fun onViewerAddressChanged(address: String) {
        updateContent { it.copy(viewerAddress = address) }
        DebugAddressHolder.setViewerAddress(0, address)
        viewModelScope.launch {
            saveDebugAddressSelections()
            refreshDebugSessionContext()
        }
    }

    fun onViewerAddress2Changed(address: String) {
        updateContent { it.copy(viewerAddress2 = address) }
        DebugAddressHolder.setViewerAddress(1, address)
        viewModelScope.launch { saveDebugAddressSelections() }
    }

    fun onViewerAddress3Changed(address: String) {
        updateContent { it.copy(viewerAddress3 = address) }
        DebugAddressHolder.setViewerAddress(2, address)
        viewModelScope.launch { saveDebugAddressSelections() }
    }

    fun onCreatorAddressChanged(address: String) {
        updateContent { it.copy(creatorAddress = address) }
        DebugAddressHolder.creatorAddress = address
        viewModelScope.launch {
            saveDebugAddressSelections()
            refreshDebugSessionContext()
        }
    }

    fun onDepositAmountChanged(amount: String) {
        updateContent { it.copy(depositAmountUsdc = amount) }
    }

    private fun loadAccountAddresses() {
        viewModelScope.launch {
            runCatching {
                val addresses =
                    getLocalAccounts()
                        .filter {
                            it is LocalAccount.HdKey ||
                                it is LocalAccount.Algo25 ||
                                it is LocalAccount.Falcon24 ||
                                it is LocalAccount.Falcon25
                        }.map { it.address }
                addresses to debugAddressSelectionsUseCase.get()
            }.onSuccess { (addresses, savedSelections) ->
                updateContent { current ->
                    val used = mutableSetOf<String>()
                    val viewer =
                        savedSelections.viewerAddress
                            .takeIf { it in addresses && it !in used }
                            ?: addresses.firstOrNull { it !in used }.orEmpty()
                    if (viewer.isNotBlank()) used.add(viewer)

                    val creator =
                        savedSelections.creatorAddress
                            .takeIf { it in addresses && it !in used }
                            ?: addresses.firstOrNull { it !in used }.orEmpty()
                    if (creator.isNotBlank()) used.add(creator)

                    val viewer2 =
                        savedSelections.viewerAddress2
                            .takeIf { it in addresses && it !in used }
                            .orEmpty()
                    if (viewer2.isNotBlank()) used.add(viewer2)

                    val viewer3 =
                        savedSelections.viewerAddress3
                            .takeIf { it in addresses && it !in used }
                            .orEmpty()
                    if (viewer3.isNotBlank()) used.add(viewer3)

                    DebugAddressHolder.setViewerAddress(0, viewer)
                    DebugAddressHolder.setViewerAddress(1, viewer2)
                    DebugAddressHolder.setViewerAddress(2, viewer3)
                    DebugAddressHolder.creatorAddress = creator
                    current.copy(
                        accountAddresses = addresses,
                        viewerAddress = viewer,
                        viewerAddress2 = viewer2,
                        viewerAddress3 = viewer3,
                        creatorAddress = creator,
                    )
                }
                saveDebugAddressSelections()
                refreshDebugSessionContext()
                if (addresses.size < 2) {
                    sendStatus("❌ At least two signable local Algorand accounts are required.")
                }
            }.onFailure { err ->
                Napier.e("[LOAD_ACCOUNTS_ERR]", err, tag = TAG)
                sendStatus("❌ Failed to load local accounts: ${err.message.orEmpty()}")
            }
        }
    }

    fun addAmountToSessionVault() {
        val content = contentState()
        val viewer = content.viewerAddress.trim()
        val creator = content.creatorAddress.trim()
        val amountUsdc = content.depositAmountUsdc.trim().toDoubleOrNull() ?: 1.0
        val depositMicroUsdc = (amountUsdc * MICRO_USDC_MULTIPLIER).toLong()

        if (!validateViewerAndCreator(content)) return

        viewModelScope.launch {
            setLoading(true)
            val vaultContext = getSessionVaultContextUseCase()
            EscrowSessionVaultHybridManagerClient.configureForNetwork(vaultContext.network)
            sendStatus("Depositing $amountUsdc USDC from viewer to ${vaultContext.networkLabel} Session Vault…")
            Napier.d(
                "[ADD_TO_VAULT_CONTEXT] viewer=$viewer creator=$creator appId=${vaultContext.appId} usdcAssetId=${vaultContext.usdcAssetId}",
                tag = TAG,
            )
            try {
                val signer = mppWalletSignerUseCase(viewer)
                if (signer != null) {
                    refreshDebugSessionContext(signer)

                    val result =
                        withContext(Dispatchers.Default) {
                            MppPayments.openSessionAndDeposit(
                                signer = signer,
                                viewerAddress = viewer,
                                depositAmountMicroUsdc = depositMicroUsdc,
                            )
                        }
                    result
                        .onSuccess { txId ->
                            if (refreshDebugSessionContext(signer, registerAuthorizedSigner = true)) {
                                sendStatus("✅ Deposited $amountUsdc USDC to Session Vault!\nTxId: $txId")
                                Napier.d("[ADD_TO_VAULT_OK] txId=$txId", tag = TAG)
                            }
                        }.onFailure { err ->
                            showError(PaymentError.from(err), "ADD_TO_VAULT_ERR", err)
                        }
                } else {
                    showError(PaymentError.SignerNotFound(viewer), "ADD_TO_VAULT_NO_SIGNER")
                }
            } catch (e: Exception) {
                showError(PaymentError.from(e), "ADD_TO_VAULT_EXCEPTION", e)
            } finally {
                setLoading(false)
            }
        }
    }

    fun fetchSessionVaultRemainingBalance() {
        val content = contentState()
        val viewer = content.viewerAddress.trim()

        if (!validateViewerAndCreator(content)) return

        viewModelScope.launch {
            setLoading(true)
            sendStatus("Fetching Session Vault balance…")
            try {
                val viewerSigner =
                    mppWalletSignerUseCase(viewer) ?: run {
                        showError(PaymentError.SignerNotFound(viewer), "FETCH_BALANCE_NO_VIEWER_SIGNER")
                        return@launch
                    }
                refreshDebugSessionContext(viewerSigner)
                val remaining =
                    withContext(Dispatchers.Default) {
                        //  val vaultContext = getSessionVaultContextUseCase()
                        MppPayments.getRemainingBalanceFromSessionVault(
                            viewerAddress = viewer,
                        )
                    }
                updateContent { it.copy(remainingBalance = remaining) }
                val usdc = remaining / MICRO_USDC_MULTIPLIER.toDouble()
                sendStatus("✅ Remaining balance: $usdc USDC\n($remaining microUSDC)")
                Napier.d("[FETCH_BALANCE_OK] remaining=$remaining", tag = TAG)
            } catch (e: Exception) {
                showError(PaymentError.from(e), "FETCH_BALANCE_ERR", e)
            } finally {
                setLoading(false)
            }
        }
    }

    /** Signs a higher cumulative voucher locally; no on-chain transaction is sent. */
    fun updateVoucher() {
        val content = contentState()
        val viewer = content.viewerAddress.trim()
        val creator = content.creatorAddress.trim()
        val amountUsdc = content.depositAmountUsdc.trim().toDoubleOrNull() ?: 1.0
        val incrementMicroUsdc = (amountUsdc * MICRO_USDC_MULTIPLIER).toLong()

        if (!validateViewerAndCreator(content)) return

        viewModelScope.launch {
            setLoading(true)
            try {
                val viewerSigner =
                    mppWalletSignerUseCase(viewer) ?: run {
                        showError(PaymentError.SignerNotFound(viewer), "LOCAL_VOUCHER_NO_VIEWER_SIGNER")
                        return@launch
                    }
                if (!refreshDebugSessionContext(viewerSigner)) return@launch
                val snapshot =
                    withContext(Dispatchers.Default) {
                        MppPayments.getSessionProgressSnapshotFromVault()
                    } ?: run {
                        showError(PaymentError.SessionNotFound, "LOCAL_VOUCHER_NO_SNAPSHOT")
                        return@launch
                    }
                val previousAmount = contentState().pendingVoucher?.cumulativeAmountMicroUsdc ?: snapshot.lastSettledMicroUsdc
                val cumulativeAmount = previousAmount + incrementMicroUsdc
                if (cumulativeAmount > snapshot.totalDepositMicroUsdc) {
                    sendStatus("❌ ${PaymentError.VoucherExceedsDeposit.userMessage}")
                    return@launch
                }
                val channelId =
                    EscrowSessionVaultHybridManagerClient.channelId ?: run {
                        showError(PaymentError.ChannelNotFound, "LOCAL_VOUCHER_NO_CHANNEL_ID")
                        return@launch
                    }
                val signature =
                    viewerSigner.signMessage(
                        MppPayments.buildLogicSigSettlementVoucher(
                            channelId = channelId,
                            cumulativeAmountMicroUsdc = cumulativeAmount,
                            payeeAddress = creator,
                        ),
                    )
                updateContent {
                    it.copy(
                        pendingVoucher =
                            PendingVoucher(
                                cumulativeAmountMicroUsdc = cumulativeAmount,
                                signature = signature,
                                authorizedSignerPublicKey = viewerSigner.authorizedSignerPublicKey,
                            ),
                    )
                }
                sendStatus(
                    "✅ Local voucher updated to ${cumulativeAmount / MICRO_USDC_MULTIPLIER.toDouble()} USDC\nNo on-chain transaction sent.",
                )
            } catch (e: Exception) {
                showError(PaymentError.from(e), "LOCAL_VOUCHER_EXCEPTION", e)
            } finally {
                setLoading(false)
            }
        }
    }

    /**
     * Compiles and registers the viewer's channel-specific settlement LogicSig on-chain, using
     * the viewer's own ephemeral session key. Must run before [settleAmount] will succeed.
     */
    fun registerSettlementLogicSig() {
        val content = contentState()
        val viewer = content.viewerAddress.trim()
        val creator = content.creatorAddress.trim()

        if (!validateViewerAndCreator(content)) return

        viewModelScope.launch {
            setLoading(true)
            try {
                val viewerSigner =
                    mppWalletSignerUseCase(viewer) ?: run {
                        showError(PaymentError.SignerNotFound(viewer), "REGISTER_LOGIC_SIG_NO_VIEWER_SIGNER")
                        return@launch
                    }
                if (!refreshDebugSessionContext(viewerSigner)) return@launch
                val channelId =
                    EscrowSessionVaultHybridManagerClient.channelId ?: run {
                        showError(PaymentError.ChannelNotFound, "REGISTER_LOGIC_SIG_NO_CHANNEL_ID")
                        return@launch
                    }
                sendStatus("Registering settlement LogicSig on-chain…")
                val result =
                    withContext(Dispatchers.Default) {
                        MppPayments.registerSettlementLogicSig(
                            signer = viewerSigner,
                            payeeAddress = creator,
                            channelId = channelId,
                        )
                    }
                result
                    .onSuccess { txId ->
                        sendStatus("✅ Settlement LogicSig registered\n\nTxId:\n$txId")
                        Napier.d("[REGISTER_LOGIC_SIG_OK] txId=$txId", tag = TAG)
                    }.onFailure { err ->
                        showError(PaymentError.from(err), "REGISTER_LOGIC_SIG_ERR", err)
                    }
            } catch (e: Exception) {
                showError(PaymentError.from(e), "REGISTER_LOGIC_SIG_EXCEPTION", e)
            } finally {
                setLoading(false)
            }
        }
    }

    /** Submits the latest locally signed voucher through the channel LogicSig. */
    fun settleAmount() {
        val content = contentState()
        val viewer = content.viewerAddress.trim()
        val creator = content.creatorAddress.trim()

        if (!validateViewerAndCreator(content)) return

        viewModelScope.launch {
            setLoading(true)
            try {
                val viewerSigner =
                    mppWalletSignerUseCase(viewer) ?: run {
                        showError(PaymentError.SignerNotFound(viewer), "SETTLE_NO_VIEWER_SIGNER")
                        return@launch
                    }
                if (!refreshDebugSessionContext(viewerSigner)) return@launch

                val pendingVoucher =
                    contentState().pendingVoucher ?: run {
                        sendStatus("❌ Update Voucher first to create an off-chain signed voucher.")
                        return@launch
                    }
                val channelId =
                    EscrowSessionVaultHybridManagerClient.channelId ?: run {
                        showError(PaymentError.ChannelNotFound, "SETTLE_NO_CHANNEL_ID")
                        return@launch
                    }
                sendStatus("Settling signed voucher to creator…")
                val settleResult =
                    withContext(Dispatchers.Default) {
                        MppPayments.settleFromLogicSig(
                            funderSigner = viewerSigner,
                            cumulativeAmountMicroUsdc = pendingVoucher.cumulativeAmountMicroUsdc,
                            voucherSignature = pendingVoucher.signature,
                            authorizedSignerPublicKey = pendingVoucher.authorizedSignerPublicKey,
                            payeeAddress = creator,
                            channelId = channelId,
                        )
                    }
                settleResult
                    .onSuccess { txId ->
                        updateContent { it.copy(pendingVoucher = null) }
                        sendStatus("✅ Settlement successful\n\nTxId:\n$txId")
                        Napier.d(
                            "[LSIG_SETTLE_OK] txId=$txId cumulativeAmount=${pendingVoucher.cumulativeAmountMicroUsdc}",
                            tag = TAG,
                        )
                    }.onFailure { err ->
                        showError(PaymentError.from(err), "LSIG_SETTLE_ERR", err)
                    }
            } catch (e: Exception) {
                showError(PaymentError.from(e), "LSIG_SETTLE_EXCEPTION", e)
            } finally {
                setLoading(false)
            }
        }
    }

    fun closeSessionVault() {
        val creator = contentState().creatorAddress.trim()

        viewModelScope.launch {
            setLoading(true)
            sendStatus("Closing session vault…")
            try {
                val creatorSigner =
                    mppWalletSignerUseCase(creator) ?: run {
                        showError(PaymentError.SignerNotFound(creator), "CLOSE_NO_CREATOR_SIGNER")
                        return@launch
                    }

                val channelId =
                    EscrowSessionVaultHybridManagerClient.channelId ?: run {
                        showError(PaymentError.ChannelNotFound, "CLOSE_NO_CHANNEL_ID")
                        return@launch
                    }

                val result =
                    withContext(Dispatchers.Default) {
                        EscrowSessionVaultHybridManagerClient.close(
                            signer = creatorSigner,
                            channelId = channelId,
                        )
                    }

                result
                    .onSuccess { txId ->
                        sendStatus("✅ Session vault closed\n\nTxId:\n$txId")
                        Napier.d("[CLOSE_OK] txId=$txId", tag = TAG)
                    }.onFailure { err ->
                        showError(PaymentError.from(err), "CLOSE_ERR", err)
                    }
            } catch (e: Exception) {
                showError(PaymentError.from(e), "CLOSE_EXCEPTION", e)
            } finally {
                setLoading(false)
            }
        }
    }

    fun requestCloseSessionVault() {
        val viewer = contentState().viewerAddress.trim()

        viewModelScope.launch {
            setLoading(true)
            sendStatus("Requesting close of Session Vault…")
            try {
                val viewerSigner =
                    mppWalletSignerUseCase(viewer) ?: run {
                        showError(
                            PaymentError.SignerNotFound(viewer),
                            "REQUEST_CLOSE_NO_VIEWER_SIGNER",
                        )
                        return@launch
                    }

                val channelId =
                    EscrowSessionVaultHybridManagerClient.channelId ?: run {
                        showError(PaymentError.ChannelNotFound, "CLOSE_NO_CHANNEL_ID")
                        return@launch
                    }

                val result =
                    withContext(Dispatchers.Default) {
                        EscrowSessionVaultHybridManagerClient.requestClose(
                            signer = viewerSigner,
                            channelId = channelId,
                        )
                    }

                result
                    .onSuccess { txId ->
                        sendStatus("✅ Requested for close of Session Vault\n\nTxId:\n$txId")
                        Napier.d("[REQUEST_CLOSE_OK] txId=$txId", tag = TAG)
                    }.onFailure { err ->
                        showError(PaymentError.from(err), "REQUEST_CLOSE_ERR", err)
                    }
            } catch (e: Exception) {
                showError(PaymentError.from(e), "REQUEST_CLOSE_EXCEPTION", e)
            } finally {
                setLoading(false)
            }
        }
    }

    fun requestWithdraw() {
        val viewer = contentState().viewerAddress.trim()

        viewModelScope.launch {
            setLoading(true)
            sendStatus("Requesting withdraw from Session Vault…")
            try {
                val viewerSigner =
                    mppWalletSignerUseCase(viewer) ?: run {
                        showError(
                            PaymentError.SignerNotFound(viewer),
                            "REQUEST_WITHDRAW_NO_VIEWER_SIGNER",
                        )
                        return@launch
                    }

                val channelId =
                    EscrowSessionVaultHybridManagerClient.channelId ?: run {
                        showError(PaymentError.ChannelNotFound, "REQUEST_WITHDRAW_NO_CHANNEL_ID")
                        return@launch
                    }

                val result =
                    withContext(Dispatchers.Default) {
                        EscrowSessionVaultHybridManagerClient.withdraw(
                            signer = viewerSigner,
                            channelId = channelId,
                        )
                    }

                result
                    .onSuccess { txId ->
                        sendStatus("✅ Requested withdraw from Session Vault\n\nTxId:\n$txId")
                        Napier.d("[REQUEST_WITHDRAW_OK] txId=$txId", tag = TAG)
                    }.onFailure { err ->
                        val parsed = PaymentError.from(err)
                        if (parsed is PaymentError.BroadcastFailed || parsed is PaymentError.Unknown) {
                            sendStatus(
                                "❌ Withdraw was rejected by the contract.\n\n" +
                                    "Withdraw is the viewer's forced-close path. Make sure you:\n" +
                                    "1. Tapped 'Request Close' first (the viewer must be the payer).\n" +
                                    "2. Waited for the ~15-minute close grace period to elapse.\n\n" +
                                    "Then try 'Request Withdraw' again.",
                            )
                            Napier.e(
                                "[REQUEST_WITHDRAW_ERR] ${parsed::class.simpleName}",
                                err,
                                tag = TAG,
                            )
                        } else {
                            showError(parsed, "REQUEST_WITHDRAW_ERR", err)
                        }
                    }
            } catch (e: Exception) {
                showError(PaymentError.from(e), "REQUEST_WITHDRAW_EXCEPTION", e)
            } finally {
                setLoading(false)
            }
        }
    }

    private suspend fun saveDebugAddressSelections() {
        debugAddressSelectionsUseCase.save(
            DebugAddressSelections(
                creatorAddress = DebugAddressHolder.creatorAddress,
                viewerAddress = DebugAddressHolder.getViewerAddress(0),
                viewerAddress2 = DebugAddressHolder.getViewerAddress(1),
                viewerAddress3 = DebugAddressHolder.getViewerAddress(2),
            ),
        )
    }

    private suspend fun refreshDebugSessionContext(
        signer: MppWalletSigner? = null,
        registerAuthorizedSigner: Boolean = false,
    ): Boolean {
        val content = contentState()
        val viewer = content.viewerAddress.trim()
        if (viewer.isBlank() || viewer == content.creatorAddress.trim()) return false
        val viewerSigner = signer ?: mppWalletSignerUseCase(viewer) ?: return false
        configureDebugSessionContext(content, viewerSigner.authorizedSignerPublicKey)

        if (!registerAuthorizedSigner) return true

        val result =
            withContext(Dispatchers.Default) {
                MppPayments.setAuthorizedSignerForSession(
                    signer = viewerSigner,
                    viewerAddress = viewer,
                    authorizedSignerPublicKey = viewerSigner.authorizedSignerPublicKey,
                )
            }
        if (result.isFailure) {
            val error = result.exceptionOrNull()
            showError(
                PaymentError.from(error ?: Exception("Failed to authorize signer")),
                "SET_AUTHORIZED_SIGNER_ERR",
                error,
            )
            return false
        }

        // Must run after setAuthorizedSignerForSession: the settlement LogicSig is compiled with
        // this signer's session key, and settlement always fails until it's registered on-chain.
        val registerResult =
            withContext(Dispatchers.Default) {
                MppPayments.registerSettlementLogicSig(signer = viewerSigner)
            }
        if (registerResult.isFailure) {
            val error = registerResult.exceptionOrNull()
            showError(
                PaymentError.from(error ?: Exception("Failed to register settlement LogicSig")),
                "REGISTER_LOGIC_SIG_ERR",
                error,
            )
            return false
        }
        return true
    }

    private fun configureDebugSessionContext(
        content: ViewState.Content,
        authorizedSignerPublicKey: ByteArray,
    ) {
        val viewer = content.viewerAddress.trim()
        val creator = content.creatorAddress.trim()
        if (viewer.isBlank() || creator.isBlank() || viewer == creator) return

        EscrowSessionVaultHybridManagerClient.hostAddress = creator
        EscrowSessionVaultHybridManagerClient.salt = EscrowSessionVaultHybridManagerClient.defaultSalt
        EscrowSessionVaultHybridManagerClient.initializeChannelId(
            payerAddress = viewer,
            payeeAddress = creator,
            authorizedSignerPublicKey = authorizedSignerPublicKey,
        )
        Napier.d(
            "[DEBUG_SESSION_CONTEXT_REFRESHED] viewer=$viewer creator=$creator " +
                "channelIdLength=${EscrowSessionVaultHybridManagerClient.channelId?.size} " +
                "saltLength=${EscrowSessionVaultHybridManagerClient.salt?.size}",
            tag = TAG,
        )
    }

    private fun validateViewerAndCreator(content: ViewState.Content): Boolean {
        val viewer = content.viewerAddress.trim()
        val creator = content.creatorAddress.trim()
        val errorMessage =
            when {
                content.accountAddresses.size < 2 -> "❌ At least two signable local Algorand accounts are required."
                viewer.isBlank() || creator.isBlank() -> "❌ Viewer and Creator addresses are required."
                viewer == creator -> "❌ Viewer and Creator addresses must be different accounts."
                else -> null
            }
        if (errorMessage != null) {
            sendStatus(errorMessage)
        }
        return errorMessage == null
    }

    private fun showError(
        error: PaymentError,
        logTag: String,
        cause: Throwable? = null,
    ) {
        sendStatus("❌ ${error.userMessage}")
        Napier.e(
            "[$logTag] ${error::class.simpleName}",
            cause ?: Throwable(error.userMessage),
            tag = TAG,
        )
    }

    private fun contentState(): ViewState.Content = state.value as ViewState.Content

    private fun updateContent(block: (ViewState.Content) -> ViewState.Content) {
        stateDelegate.updateState { current -> block(current as ViewState.Content) }
    }

    private fun setLoading(isLoading: Boolean) {
        updateContent { it.copy(isLoading = isLoading) }
    }

    private fun sendStatus(message: String) {
        eventDelegate.sendEvent(viewModelScope, ViewEvent.ShowStatusMessage(message))
    }

    sealed interface ViewState {
        data class Content(
            val accountAddresses: List<String> = emptyList(),
            val viewerAddress: String = "",
            val viewerAddress2: String = "",
            val viewerAddress3: String = "",
            val creatorAddress: String = "",
            val depositAmountUsdc: String = "0.1",
            val remainingBalance: Long? = null,
            val pendingVoucher: PendingVoucher? = null,
            val isLoading: Boolean = false,
        ) : ViewState {
            val canRunVaultActions: Boolean
                get() =
                    creatorAddress.isNotBlank() &&
                        (
                            viewerAddress.isNotBlank() ||
                                viewerAddress2.isNotBlank() ||
                                viewerAddress3.isNotBlank()
                        )
        }
    }

    sealed interface ViewEvent {
        data class ShowStatusMessage(
            val message: String,
        ) : ViewEvent
    }
}
