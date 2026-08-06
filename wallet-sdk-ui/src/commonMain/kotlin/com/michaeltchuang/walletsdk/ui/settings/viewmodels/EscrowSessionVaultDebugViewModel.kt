package com.michaeltchuang.walletsdk.ui.settings.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetLocalAccounts
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateViewModel
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetSessionVaultContextUseCase
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.MppWalletSignerUseCase
import com.michaeltchuang.walletsdk.core.railmpp.smartcontract.EscrowSessionVaultManagerClient
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
) : ViewModel(),
    StateViewModel<EscrowSessionVaultDebugViewModel.ViewState> by stateDelegate,
    EventViewModel<EscrowSessionVaultDebugViewModel.ViewEvent> by eventDelegate {
    companion object Companion {
        private const val TAG = "EscrowSessionVaultDebugViewModel"
        private const val MICRO_USDC_MULTIPLIER = 1_000_000L
    }

    init {
        stateDelegate.setDefaultState(ViewState.Content())
        loadAccountAddresses()
    }

    fun onViewerAddressChanged(address: String) {
        updateContent { it.copy(viewerAddress = address) }
        DebugAddressHolder.viewerAddress = address
        refreshDebugSessionContext()
    }

    fun onCreatorAddressChanged(address: String) {
        updateContent { it.copy(creatorAddress = address) }
        DebugAddressHolder.creatorAddress = address
        refreshDebugSessionContext()
    }

    fun onDepositAmountChanged(amount: String) {
        updateContent { it.copy(depositAmountUsdc = amount) }
    }

    private fun loadAccountAddresses() {
        viewModelScope.launch {
            runCatching {
                getLocalAccounts()
                    .filter {
                        it is LocalAccount.HdKey ||
                            it is LocalAccount.Algo25 ||
                            it is LocalAccount.Falcon24 ||
                            it is LocalAccount.Falcon25
                    }.map { it.address }
            }.onSuccess { addresses ->
                updateContent { current ->
                    val viewer = current.viewerAddress.ifBlank { addresses.getOrNull(0).orEmpty() }
                    val creator = current.creatorAddress.ifBlank { addresses.getOrNull(1).orEmpty() }
                    DebugAddressHolder.viewerAddress = viewer
                    DebugAddressHolder.creatorAddress = creator
                    current.copy(
                        accountAddresses = addresses,
                        viewerAddress = viewer,
                        creatorAddress = creator,
                    )
                }
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
            EscrowSessionVaultManagerClient.configureForNetwork(vaultContext.network)
            sendStatus("Depositing $amountUsdc USDC from viewer to ${vaultContext.networkLabel} Session Vault…")
            Napier.d(
                "[ADD_TO_VAULT_CONTEXT] viewer=$viewer creator=$creator appId=${vaultContext.appId} usdcAssetId=${vaultContext.usdcAssetId}",
                tag = TAG,
            )
            try {
                val signer = mppWalletSignerUseCase(viewer)
                if (signer != null) {
                    refreshDebugSessionContext(signer.authorizedSignerPublicKey)

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
                            sendStatus("✅ Deposited $amountUsdc USDC to Session Vault!\nTxId: $txId")
                            Napier.d("[ADD_TO_VAULT_OK] txId=$txId", tag = TAG)
                        }.onFailure { err ->
                            showError(PaymentError.Companion.from(err), "ADD_TO_VAULT_ERR", err)
                        }
                } else {
                    showError(PaymentError.SignerNotFound(viewer), "ADD_TO_VAULT_NO_SIGNER")
                }
            } catch (e: Exception) {
                showError(PaymentError.Companion.from(e), "ADD_TO_VAULT_EXCEPTION", e)
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
                showError(PaymentError.Companion.from(e), "FETCH_BALANCE_ERR", e)
            } finally {
                setLoading(false)
            }
        }
    }

    fun updateVoucher() {
        val content = contentState()
        val viewer = content.viewerAddress.trim()
        val amountUsdc = content.depositAmountUsdc.trim().toDoubleOrNull() ?: 1.0
        val requestedIncrementMicroUsdc = (amountUsdc * MICRO_USDC_MULTIPLIER).toLong()

        if (!validateViewerAndCreator(content)) return

        viewModelScope.launch {
            setLoading(true)
            try {
                sendStatus("Preparing voucher update...")

                val viewerSigner =
                    mppWalletSignerUseCase(viewer) ?: run {
                        showError(
                            PaymentError.SignerNotFound(viewer),
                            "UPDATE_VOUCHER_NO_VIEWER_SIGNER",
                        )
                        return@launch
                    }

                val snapshot =
                    withContext(Dispatchers.Default) {
                        MppPayments.getSessionProgressSnapshotFromVault()
                    } ?: run {
                        showError(PaymentError.SessionNotFound, "UPDATE_VOUCHER_NO_SNAPSHOT")
                        return@launch
                    }

                val totalDeposit = snapshot.totalDepositMicroUsdc
                val lastSettled = snapshot.lastSettledMicroUsdc
                val latestVoucher = snapshot.latestVoucherAmountMicroUsdc

                Napier.d(
                    "[SESSION_STATE] totalDeposit=$totalDeposit lastSettled=$lastSettled latestVoucher=$latestVoucher",
                    tag = TAG,
                )

                val newCumulative = latestVoucher + requestedIncrementMicroUsdc

                if (newCumulative > totalDeposit) {
                    val depositUsdc = totalDeposit / 1_000_000.0
                    val requestedUsdc = newCumulative / 1_000_000.0
                    sendStatus(
                        "❌ ${PaymentError.VoucherExceedsDeposit.userMessage}" +
                            "\n\nDeposited: $depositUsdc USDC  |  Requested: $requestedUsdc USDC",
                    )
                    return@launch
                }

                if (newCumulative <= lastSettled) {
                    sendStatus("❌ ${PaymentError.NothingToSettle.userMessage}")
                    return@launch
                }
                val channelId = EscrowSessionVaultManagerClient.channelId
                if (channelId == null) {
                    Napier.e("channelId is null", tag = TAG)
                    return@launch
                }
                val settleMessage =
                    MppPayments.settleMessage(
                        cumulativeAmountMicroUsdc = newCumulative,
                        channelId = channelId,
                    )

                val viewerSignature = viewerSigner.signMessage(settleMessage)
                Napier.d("[SIGNATURE_CREATED] sigLen=${viewerSignature.size}", tag = TAG)

                withContext(Dispatchers.Default) {
                    MppPayments.updateVoucherOnChain(
                        signer = viewerSigner,
                        viewerAddress = viewer,
                        totalAmountUsedMicroUsdc = newCumulative,
                        signature = viewerSignature,
                    )
                }.onSuccess { txId ->
                    Napier.d("[UPDATE_VOUCHER_OK] txId=$txId", tag = TAG)
                    sendStatus("✅ Voucher updated!\nTxId: $txId")
                }.onFailure { err ->
                    showError(PaymentError.Companion.from(err), "UPDATE_VOUCHER_ERR", err)
                }
            } catch (e: Exception) {
                showError(PaymentError.Companion.from(e), "UPDATE_VOUCHER_EXCEPTION", e)
            } finally {
                setLoading(false)
            }
        }
    }

    fun verifyVoucherSignature() {
        val content = contentState()
        val viewer = content.viewerAddress.trim()
        val amountUsdc = content.depositAmountUsdc.trim().toDoubleOrNull() ?: 1.0
        val depositMicroUsdc = (amountUsdc * MICRO_USDC_MULTIPLIER).toLong()

        if (!validateViewerAndCreator(content)) return

        viewModelScope.launch {
            setLoading(true)
            sendStatus("Verifying voucher signature…")
            try {
                val viewerSigner = mppWalletSignerUseCase(viewer)
                if (viewerSigner != null) {
                    val channelId = EscrowSessionVaultManagerClient.channelId
                    if (channelId == null) {
                        Napier.e("channelId is null", tag = TAG)
                        return@launch
                    }
                    val settleMessage =
                        MppPayments.settleMessage(
                            cumulativeAmountMicroUsdc = depositMicroUsdc,
                            channelId = channelId,
                        )
                    val signature = viewerSigner.signMessage(settleMessage)

                    val result =
                        withContext(Dispatchers.Default) {
                            MppPayments.verifySettleSignature(
                                signer = viewerSigner,
                                cumulativeAmountMicroUsdc = depositMicroUsdc,
                                signature = signature,
                            )
                        }
                    result
                        .onSuccess {
                            sendStatus("✅ Signature verified!")
                            Napier.d("[VERIFY_SIGNATURE_OK]", tag = TAG)
                        }.onFailure { err ->
                            showError(PaymentError.Companion.from(err), "VERIFY_SIGNATURE_ERR", err)
                        }
                } else {
                    showError(PaymentError.SignerNotFound(viewer), "VERIFY_SIGNATURE_NO_SIGNER")
                }
            } catch (e: Exception) {
                showError(PaymentError.Companion.from(e), "VERIFY_SIGNATURE_EXCEPTION", e)
            } finally {
                setLoading(false)
            }
        }
    }

    fun settleAmount() {
        val content = contentState()
        val creator = content.creatorAddress.trim()

        if (!validateViewerAndCreator(content)) return

        viewModelScope.launch {
            setLoading(true)
            try {
                sendStatus("Preparing settlement...")

                val snapshot =
                    withContext(Dispatchers.Default) {
                        MppPayments.getSessionProgressSnapshotFromVault()
                    } ?: run {
                        showError(PaymentError.SessionNotFound, "SETTLE_NO_SNAPSHOT")
                        return@launch
                    }

                val totalDeposit = snapshot.totalDepositMicroUsdc
                val lastSettled = snapshot.lastSettledMicroUsdc
                val latestVoucher = snapshot.latestVoucherAmountMicroUsdc

                Napier.d(
                    "[SESSION_STATE] totalDeposit=$totalDeposit lastSettled=$lastSettled latestVoucher=$latestVoucher",
                    tag = TAG,
                )

                if (latestVoucher > totalDeposit) {
                    val depositUsdc = totalDeposit / 1_000_000.0
                    val requestedUsdc = latestVoucher / 1_000_000.0
                    sendStatus(
                        "❌ ${PaymentError.VoucherExceedsDeposit.userMessage}" +
                            "\n\nDeposited: $depositUsdc USDC  |  Requested: $requestedUsdc USDC",
                    )
                    return@launch
                }

                if (latestVoucher <= lastSettled) {
                    sendStatus("❌ ${PaymentError.NothingToSettle.userMessage}")
                    return@launch
                }

                val channelId = EscrowSessionVaultManagerClient.channelId
                if (channelId == null) {
                    Napier.e("channelId is null", tag = TAG)
                    return@launch
                }

                val creatorSigner =
                    mppWalletSignerUseCase(creator) ?: run {
                        showError(PaymentError.SignerNotFound(creator), "SETTLE_NO_CREATOR_SIGNER")
                        return@launch
                    }

                sendStatus("Settling to creator…")
                val settleResult =
                    withContext(Dispatchers.Default) {
                        MppPayments.settleLatestVoucher(signer = creatorSigner)
                    }

                settleResult
                    .onSuccess { txId ->
                        sendStatus("✅ Settlement successful\n\nTxId:\n$txId")
                        Napier.d("[SETTLE_OK] txId=$txId", tag = TAG)
                    }.onFailure { err ->
                        showError(PaymentError.Companion.from(err), "SETTLE_ERR", err)
                    }
            } catch (e: Exception) {
                showError(PaymentError.Companion.from(e), "SETTLE_EXCEPTION", e)
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
                    EscrowSessionVaultManagerClient.channelId ?: run {
                        showError(PaymentError.ChannelNotFound, "CLOSE_NO_CHANNEL_ID")
                        return@launch
                    }

                val result =
                    withContext(Dispatchers.Default) {
                        EscrowSessionVaultManagerClient.close(
                            signer = creatorSigner,
                            channelId = channelId,
                        )
                    }

                result
                    .onSuccess { txId ->
                        sendStatus("✅ Session vault closed\n\nTxId:\n$txId")
                        Napier.d("[CLOSE_OK] txId=$txId", tag = TAG)
                    }.onFailure { err ->
                        showError(PaymentError.Companion.from(err), "CLOSE_ERR", err)
                    }
            } catch (e: Exception) {
                showError(PaymentError.Companion.from(e), "CLOSE_EXCEPTION", e)
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
                    EscrowSessionVaultManagerClient.channelId ?: run {
                        showError(PaymentError.ChannelNotFound, "CLOSE_NO_CHANNEL_ID")
                        return@launch
                    }

                val result =
                    withContext(Dispatchers.Default) {
                        EscrowSessionVaultManagerClient.requestClose(
                            signer = viewerSigner,
                            channelId = channelId,
                        )
                    }

                result
                    .onSuccess { txId ->
                        sendStatus("✅ Requested for close of Session Vault\n\nTxId:\n$txId")
                        Napier.d("[REQUEST_CLOSE_OK] txId=$txId", tag = TAG)
                    }.onFailure { err ->
                        showError(PaymentError.Companion.from(err), "REQUEST_CLOSE_ERR", err)
                    }
            } catch (e: Exception) {
                showError(PaymentError.Companion.from(e), "REQUEST_CLOSE_EXCEPTION", e)
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
                    EscrowSessionVaultManagerClient.channelId ?: run {
                        showError(PaymentError.ChannelNotFound, "REQUEST_WITHDRAW_NO_CHANNEL_ID")
                        return@launch
                    }

                val result =
                    withContext(Dispatchers.Default) {
                        EscrowSessionVaultManagerClient.withdraw(
                            signer = viewerSigner,
                            channelId = channelId,
                        )
                    }

                result
                    .onSuccess { txId ->
                        sendStatus("✅ Requested withdraw from Session Vault\n\nTxId:\n$txId")
                        Napier.d("[REQUEST_WITHDRAW_OK] txId=$txId", tag = TAG)
                    }.onFailure { err ->
                        val parsed = PaymentError.Companion.from(err)
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
                showError(PaymentError.Companion.from(e), "REQUEST_WITHDRAW_EXCEPTION", e)
            } finally {
                setLoading(false)
            }
        }
    }

    private fun refreshDebugSessionContext(authorizedSignerPublicKey: ByteArray? = null) {
        if (authorizedSignerPublicKey != null) {
            configureDebugSessionContext(
                content = contentState(),
                authorizedSignerPublicKey = authorizedSignerPublicKey,
            )
            return
        }

        viewModelScope.launch {
            val content = contentState()
            val viewer = content.viewerAddress.trim()
            if (viewer.isBlank() || viewer == content.creatorAddress.trim()) return@launch
            val signer = mppWalletSignerUseCase(viewer) ?: return@launch
            configureDebugSessionContext(content, signer.authorizedSignerPublicKey)
        }
    }

    private fun configureDebugSessionContext(
        content: ViewState.Content,
        authorizedSignerPublicKey: ByteArray,
    ) {
        val viewer = content.viewerAddress.trim()
        val creator = content.creatorAddress.trim()
        if (viewer.isBlank() || creator.isBlank() || viewer == creator) return

        EscrowSessionVaultManagerClient.hostAddress = creator
        EscrowSessionVaultManagerClient.salt = EscrowSessionVaultManagerClient.defaultSalt
        EscrowSessionVaultManagerClient.initializeChannelId(
            payerAddress = viewer,
            payeeAddress = creator,
            authorizedSignerPublicKey = authorizedSignerPublicKey,
        )
        Napier.d(
            "[DEBUG_SESSION_CONTEXT_REFRESHED] viewer=$viewer creator=$creator " +
                "channelIdLength=${EscrowSessionVaultManagerClient.channelId?.size} " +
                "saltLength=${EscrowSessionVaultManagerClient.salt?.size}",
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
            val creatorAddress: String = "",
            val depositAmountUsdc: String = "0.1",
            val remainingBalance: Long? = null,
            val isLoading: Boolean = false,
        ) : ViewState {
            val canRunVaultActions: Boolean
                get() =
                    accountAddresses.size >= 2 &&
                        viewerAddress.isNotBlank() &&
                        creatorAddress.isNotBlank() &&
                        viewerAddress != creatorAddress
        }
    }

    sealed interface ViewEvent {
        data class ShowStatusMessage(
            val message: String,
        ) : ViewEvent
    }
}
