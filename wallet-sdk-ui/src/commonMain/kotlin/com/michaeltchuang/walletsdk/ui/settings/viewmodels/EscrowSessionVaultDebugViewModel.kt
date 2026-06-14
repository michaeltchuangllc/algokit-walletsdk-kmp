package com.michaeltchuang.walletsdk.ui.settings.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecases.MppWalletSignerUseCase
import com.michaeltchuang.walletsdk.core.railmpp.smartcontract.EscrowSessionVaultManagerClient
import com.michaeltchuang.walletsdk.core.railmpp.utils.MppPayments
import com.michaeltchuang.walletsdk.core.railmpp.utils.MppPayments.contractClient
import com.michaeltchuang.walletsdk.core.railmpp.utils.PaymentError
import com.michaeltchuang.walletsdk.core.railmpp.utils.RailMppConstants
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EscrowSessionVaultDebugViewModel(
    private val mppWalletSignerUseCase: MppWalletSignerUseCase,
) : ViewModel() {
    companion object Companion {
        private const val TAG = "EscrowSessionVaultDebugViewModel"
        private const val MICRO_USDC_MULTIPLIER = 1_000_000L
    }

    private val _viewerAddress =
        MutableStateFlow(
            "2MFPDQMIMIYS6CCIRMNWB6IACQL6VCFRZE7STJBP4W5Q3FLHUJHIVP3FLY",
        )
    val viewerAddress: StateFlow<String> = _viewerAddress.asStateFlow()

    private val _creatorAddress =
        MutableStateFlow(
            "EBRI466FDKE2LKEPUDAYTIRZZ7LLKT7YMZ7TG37II6CCOAJK44SKXY7EHI",
        )
    val creatorAddress: StateFlow<String> = _creatorAddress.asStateFlow()

    private val _depositAmountUsdc = MutableStateFlow("0.1")
    val depositAmountUsdc: StateFlow<String> = _depositAmountUsdc.asStateFlow()

    private val _remainingBalance = MutableStateFlow<Long?>(null)
    val remainingBalance: StateFlow<Long?> = _remainingBalance.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun onViewerAddressChanged(address: String) {
        _viewerAddress.value = address
    }

    fun onCreatorAddressChanged(address: String) {
        _creatorAddress.value = address
    }

    fun onDepositAmountChanged(amount: String) {
        _depositAmountUsdc.value = amount
    }

    fun addAmountToSessionVault() {
        val viewer = _viewerAddress.value.trim()
        val creator = _creatorAddress.value.trim()
        val amountUsdc = _depositAmountUsdc.value.trim().toDoubleOrNull() ?: 1.0
        val depositMicroUsdc = (amountUsdc * MICRO_USDC_MULTIPLIER).toLong()

        if (viewer.isBlank() || creator.isBlank()) {
            _statusMessage.value = "Error: Viewer and Creator addresses are required."
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            _statusMessage.value = "Depositing $amountUsdc USDC to Session Vault…"
            try {
                val signer = mppWalletSignerUseCase(viewer)
                if (signer != null) {
                    val result =
                        withContext(Dispatchers.Default) {
                            MppPayments.openSessionAndDeposit(
                                signer = signer,
                                viewerAddress = viewer,
                                creatorAddress = creator,
                                depositAmountMicroUsdc = depositMicroUsdc,
                            )
                        }
                    result
                        .onSuccess { txId ->
                            _statusMessage.value =
                                "✅ Deposited $amountUsdc USDC to Session Vault!\nTxId: $txId"
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
                _isLoading.value = false
            }
        }
    }

    fun fetchSessionVaultRemainingBalance() {
        val viewer = _viewerAddress.value.trim()
        val creator = _creatorAddress.value.trim()

        if (viewer.isBlank() || creator.isBlank()) {
            _statusMessage.value = "Error: Viewer and Creator addresses are required."
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            _statusMessage.value = "Fetching Session Vault balance…"
            try {
                val signer = mppWalletSignerUseCase(viewer)
                val remaining =
                    withContext(Dispatchers.Default) {
                        MppPayments.getRemainingBalanceFromSessionVault(
                            viewerAddress = viewer,
                            hostAddress = creator,
                            appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                            algodUrl = null,
                            authorizedSignerPublicKey = signer?.authorizedSignerPublicKey,
                        )
                    }
                _remainingBalance.value = remaining
                val usdc = remaining / MICRO_USDC_MULTIPLIER.toDouble()
                _statusMessage.value = "✅ Remaining balance: $usdc USDC\n($remaining microUSDC)"
                Napier.d("[FETCH_BALANCE_OK] remaining=$remaining", tag = TAG)
            } catch (e: Exception) {
                showError(PaymentError.Companion.from(e), "FETCH_BALANCE_ERR", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateVoucher() {

        val viewer = _viewerAddress.value.trim()
        val creator = _creatorAddress.value.trim()
        val amountUsdc = _depositAmountUsdc.value.trim().toDoubleOrNull() ?: 1.0
        val requestedIncrementMicroUsdc = (amountUsdc * MICRO_USDC_MULTIPLIER).toLong()

        if (viewer.isBlank() || creator.isBlank()) {
            _statusMessage.value = "Error: Viewer and Creator addresses are required."
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            try {
                _statusMessage.value = "Preparing voucher update..."

                val viewerSigner =
                    mppWalletSignerUseCase(viewer) ?: run {
                        showError(
                            PaymentError.SignerNotFound(viewer),
                            "UPDATE_VOUCHER_NO_VIEWER_SIGNER"
                        )
                        return@launch
                    }

                val snapshot =
                    withContext(Dispatchers.Default) {
                        MppPayments.getSessionProgressSnapshotFromVault(
                            viewerAddress = viewer,
                            hostAddress = creator,
                            appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                            authorizedSignerPublicKey = viewerSigner.authorizedSignerPublicKey,
                        )
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
                    _statusMessage.value =
                        "❌ ${PaymentError.VoucherExceedsDeposit.userMessage}" +
                                "\n\nDeposited: $depositUsdc USDC  |  Requested: $requestedUsdc USDC"
                    return@launch
                }

                if (newCumulative <= lastSettled) {
                    _statusMessage.value = "❌ ${PaymentError.NothingToSettle.userMessage}"
                    return@launch
                }

                val settleMessage =
                    MppPayments.settleMessage(
                        signer = viewerSigner,
                        viewerAddress = viewer,
                        hostAddress = creator,
                        cumulativeAmountMicroUsdc = newCumulative,
                    )

                val viewerSignature = viewerSigner.signMessage(settleMessage)
                Napier.d("[SIGNATURE_CREATED] sigLen=${viewerSignature.size}", tag = TAG)

                    withContext(Dispatchers.Default) {
                        MppPayments.updateVoucherOnChain(
                            signer = viewerSigner,
                            appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                            viewerAddress = viewer,
                            hostAddress = creator,
                            totalAmountUsedMicroUsdc = newCumulative,
                            signature = viewerSignature,
                        )
                    }.onSuccess { txId ->
                        Napier.d("[UPDATE_VOUCHER_OK] txId=$txId", tag = TAG)
                        _statusMessage.value = "✅ Voucher updated!\nTxId: $txId"
                    }.onFailure { err ->
                        showError(PaymentError.Companion.from(err), "UPDATE_VOUCHER_ERR", err)
                    }
            } catch (e: Exception) {
                showError(PaymentError.Companion.from(e), "UPDATE_VOUCHER_EXCEPTION", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun verifyVoucherSignature() {
        val viewer = _viewerAddress.value.trim()
        val creator = _creatorAddress.value.trim()
        val amountUsdc = _depositAmountUsdc.value.trim().toDoubleOrNull() ?: 1.0
        val depositMicroUsdc = (amountUsdc * MICRO_USDC_MULTIPLIER).toLong()

        if (viewer.isBlank() || creator.isBlank()) {
            _statusMessage.value = "Error: Viewer and Creator addresses are required."
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            _statusMessage.value = "Verifying voucher signature…"
            try {
                val viewerSigner = mppWalletSignerUseCase(viewer)
                if (viewerSigner != null) {
                    val settleMessage =
                        MppPayments.settleMessage(
                            signer = viewerSigner,
                            viewerAddress = viewer,
                            hostAddress = creator,
                            cumulativeAmountMicroUsdc = depositMicroUsdc,
                        )
                    val signature = viewerSigner.signMessage(settleMessage)

                    val result =
                        withContext(Dispatchers.Default) {
                            MppPayments.verifySettleSignature(
                                signer = viewerSigner,
                                viewerAddress = viewer,
                                hostAddress = creator,
                                cumulativeAmountMicroUsdc = depositMicroUsdc,
                                signature = signature,
                            )
                        }
                    result
                        .onSuccess {
                            _statusMessage.value = "✅ Signature verified!"
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
                _isLoading.value = false
            }
        }
    }

    fun settleAmount() {
        val viewer = _viewerAddress.value.trim()
        val creator = _creatorAddress.value.trim()
        val amountUsdc = _depositAmountUsdc.value.trim().toDoubleOrNull() ?: 1.0
        val requestedIncrementMicroUsdc = (amountUsdc * MICRO_USDC_MULTIPLIER).toLong()

        if (viewer.isBlank() || creator.isBlank()) {
            _statusMessage.value = "Error: Viewer and Creator addresses are required."
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            try {
                _statusMessage.value = "Preparing settlement..."

                val viewerSigner =
                    mppWalletSignerUseCase(viewer) ?: run {
                        showError(PaymentError.SignerNotFound(viewer), "SETTLE_NO_VIEWER_SIGNER")
                        return@launch
                    }

                val snapshot =
                    withContext(Dispatchers.Default) {
                        MppPayments.getSessionProgressSnapshotFromVault(
                            viewerAddress = viewer,
                            hostAddress = creator,
                            appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                            authorizedSignerPublicKey = viewerSigner.authorizedSignerPublicKey,
                        )
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

                val newCumulative = latestVoucher + requestedIncrementMicroUsdc

                if (newCumulative > totalDeposit) {
                    val depositUsdc = totalDeposit / 1_000_000.0
                    val requestedUsdc = newCumulative / 1_000_000.0
                    _statusMessage.value =
                        "❌ ${PaymentError.VoucherExceedsDeposit.userMessage}" +
                                "\n\nDeposited: $depositUsdc USDC  |  Requested: $requestedUsdc USDC"
                    return@launch
                }

                if (newCumulative <= lastSettled) {
                    _statusMessage.value = "❌ ${PaymentError.NothingToSettle.userMessage}"
                    return@launch
                }

                val settleMessage =
                    MppPayments.settleMessage(
                        signer = viewerSigner,
                        viewerAddress = viewer,
                        hostAddress = creator,
                        cumulativeAmountMicroUsdc = newCumulative,
                    )

                val viewerSignature = viewerSigner.signMessage(settleMessage)
                Napier.d("[SIGNATURE_CREATED] sigLen=${viewerSignature.size}", tag = TAG)

                _statusMessage.value = "Recording voucher on-chain…"
                val updateTxId =
                    withContext(Dispatchers.Default) {
                        MppPayments.updateVoucherOnChain(
                            signer = viewerSigner,
                            appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                            viewerAddress = viewer,
                            hostAddress = creator,
                            totalAmountUsedMicroUsdc = newCumulative,
                            signature = viewerSignature,
                        )
                    }.getOrElse { err ->
                        showError(PaymentError.Companion.from(err), "UPDATE_VOUCHER_ERR", err)
                        return@launch
                    }
                Napier.d("[UPDATE_VOUCHER_OK] txId=$updateTxId", tag = TAG)

                val creatorSigner =
                    mppWalletSignerUseCase(creator) ?: run {
                        showError(PaymentError.SignerNotFound(creator), "SETTLE_NO_CREATOR_SIGNER")
                        return@launch
                    }

                _statusMessage.value = "Settling to creator…"
                val settleResult =
                    withContext(Dispatchers.Default) {
                        MppPayments.settleLatestVoucher(
                            signer = creatorSigner,
                            appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                            viewerAddress = viewer,
                            hostAddress = creator,
                            authorizedSignerPublicKey = viewerSigner.authorizedSignerPublicKey,
                            algodUrl = MppPayments.TESTNET_ALGOD_URL,
                        )
                    }

                settleResult
                    .onSuccess { txId ->
                        _statusMessage.value = "✅ Settlement successful\n\nTxId:\n$txId"
                        Napier.d("[SETTLE_OK] txId=$txId", tag = TAG)
                    }.onFailure { err ->
                        showError(PaymentError.Companion.from(err), "SETTLE_ERR", err)
                    }
            } catch (e: Exception) {
                showError(PaymentError.Companion.from(e), "SETTLE_EXCEPTION", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun closeSessionVault() {
        val creator = _creatorAddress.value.trim()

        viewModelScope.launch {
            _isLoading.value = true
            _statusMessage.value = "Closing session vault…"
            try {
                val creatorSigner =
                    mppWalletSignerUseCase(creator) ?: run {
                        showError(PaymentError.SignerNotFound(creator), "CLOSE_NO_CREATOR_SIGNER")
                        return@launch
                    }

                val channelId = EscrowSessionVaultManagerClient.storedChannelId ?: run {
                    showError(PaymentError.ChannelNotFound, "CLOSE_NO_CHANNEL_ID")
                    return@launch
                }

                val result =
                    withContext(Dispatchers.Default) {
                        contractClient(
                            appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                            algodUrl = MppPayments.TESTNET_ALGOD_URL,
                        ).close(
                            signer = creatorSigner,
                            channelId = channelId,
                            algodUrl = MppPayments.TESTNET_ALGOD_URL,
                        )
                    }

                result
                    .onSuccess { txId ->
                        _statusMessage.value = "✅ Session vault closed\n\nTxId:\n$txId"
                        Napier.d("[CLOSE_OK] txId=$txId", tag = TAG)
                    }.onFailure { err ->
                        showError(PaymentError.Companion.from(err), "CLOSE_ERR", err)
                    }
            } catch (e: Exception) {
                showError(PaymentError.Companion.from(e), "CLOSE_EXCEPTION", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun requestCloseSessionVault() {
        val viewer = _viewerAddress.value.trim()

        viewModelScope.launch {
            _isLoading.value = true
            _statusMessage.value = "Requesting close of Session Vault…"
            try {
                val viewerSigner =
                    mppWalletSignerUseCase(viewer) ?: run {
                        showError(
                            PaymentError.SignerNotFound(viewer),
                            "REQUEST_CLOSE_NO_VIEWER_SIGNER"
                        )
                        return@launch
                    }

                val channelId = EscrowSessionVaultManagerClient.storedChannelId ?: run {
                    showError(PaymentError.ChannelNotFound, "CLOSE_NO_CHANNEL_ID")
                    return@launch
                }

                val result =
                    withContext(Dispatchers.Default) {
                        contractClient(
                            appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                            algodUrl = MppPayments.TESTNET_ALGOD_URL,
                        ).requestClose(
                            signer = viewerSigner,
                            channelId = channelId,
                            algodUrl = MppPayments.TESTNET_ALGOD_URL,
                        )
                    }

                result
                    .onSuccess { txId ->
                        _statusMessage.value =
                            "✅ Requested for close of Session Vault\n\nTxId:\n$txId"
                        Napier.d("[REQUEST_CLOSE_OK] txId=$txId", tag = TAG)
                    }.onFailure { err ->
                        showError(PaymentError.Companion.from(err), "REQUEST_CLOSE_ERR", err)
                    }
            } catch (e: Exception) {
                showError(PaymentError.Companion.from(e), "REQUEST_CLOSE_EXCEPTION", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun requestWithdraw() {
        val viewer = _viewerAddress.value.trim()

        viewModelScope.launch {
            _isLoading.value = true
            _statusMessage.value = "Requesting withdraw from Session Vault…"
            try {
                val viewerSigner =
                    mppWalletSignerUseCase(viewer) ?: run {
                        showError(
                            PaymentError.SignerNotFound(viewer),
                            "REQUEST_WITHDRAW_NO_VIEWER_SIGNER"
                        )
                        return@launch
                    }

                val channelId = EscrowSessionVaultManagerClient.storedChannelId ?: run {
                    showError(PaymentError.ChannelNotFound, "REQUEST_WITHDRAW_NO_CHANNEL_ID")
                    return@launch
                }

                val result =
                    withContext(Dispatchers.Default) {
                        contractClient(
                            appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                            algodUrl = MppPayments.TESTNET_ALGOD_URL,
                        ).withdraw(
                            signer = viewerSigner,
                            channelId = channelId,
                            algodUrl = MppPayments.TESTNET_ALGOD_URL,
                        )
                    }

                result
                    .onSuccess { txId ->
                        _statusMessage.value = "✅ Requested withdraw from Session Vault\n\nTxId:\n$txId"
                        Napier.d("[REQUEST_WITHDRAW_OK] txId=$txId", tag = TAG)
                    }.onFailure { err ->
                        val parsed = PaymentError.Companion.from(err)
                        // withdraw() is the payer's forced-close path. It only succeeds once
                        // `requestClose` has been called AND the 15-minute grace period has
                        if (parsed is PaymentError.BroadcastFailed || parsed is PaymentError.Unknown) {
                            _statusMessage.value =
                                "❌ Withdraw was rejected by the contract.\n\n" +
                                    "Withdraw is the viewer's forced-close path. Make sure you:\n" +
                                    "1. Tapped 'Request Close' first (the viewer must be the payer).\n" +
                                    "2. Waited for the ~15-minute close grace period to elapse.\n\n" +
                                    "Then try 'Request Withdraw' again."
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
                _isLoading.value = false
            }
        }
    }
    

    private fun showError(
        error: PaymentError,
        logTag: String,
        cause: Throwable? = null,
    ) {
        _statusMessage.value = "❌ ${error.userMessage}"
        Napier.e(
            "[$logTag] ${error::class.simpleName}",
            cause ?: Throwable(error.userMessage),
            tag = TAG
        )
    }

}
