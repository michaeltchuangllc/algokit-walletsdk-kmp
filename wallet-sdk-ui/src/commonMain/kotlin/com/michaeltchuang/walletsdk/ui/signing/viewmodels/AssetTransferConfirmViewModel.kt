package com.michaeltchuang.walletsdk.ui.signing.viewmodels

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import com.ionspin.kotlin.bignum.integer.BigInteger
import com.ionspin.kotlin.bignum.integer.toBigInteger
import com.michaeltchuang.walletsdk.core.account.domain.usecase.core.GetAccountASABalance
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAccountAlgoBalance
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAccountMinimumBalance
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetTransactionFeeForAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetTransactionSigner
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateViewModel
import com.michaeltchuang.walletsdk.core.network.model.ApiResult
import com.michaeltchuang.walletsdk.core.network.service.AssetDetailApiService
import com.michaeltchuang.walletsdk.core.transaction.domain.usecase.SendSignedTransactionUseCase
import com.michaeltchuang.walletsdk.core.transaction.model.SignedTransactionDetail
import com.michaeltchuang.walletsdk.core.transaction.model.TargetUser
import com.michaeltchuang.walletsdk.core.transaction.model.TransactionManagerResult
import com.michaeltchuang.walletsdk.core.transaction.model.TransactionSignData
import com.michaeltchuang.walletsdk.core.transaction.signmanager.TransactionSignManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.random.Random

class AssetTransferConfirmViewModel(
    private val transactionSignManager: TransactionSignManager,
    private val sendSignedTransactionUseCase: SendSignedTransactionUseCase,
    private val getTransactionSigner: GetTransactionSigner,
    private val getAccountAlgoBalance: GetAccountAlgoBalance,
    private val getAccountASABalance: GetAccountASABalance,
    private val getAccountMinimumBalance: GetAccountMinimumBalance,
    private val getTransactionFeeForAccount: GetTransactionFeeForAccount,
    private val getAssetDetailApiService: AssetDetailApiService,
    private val stateDelegate: StateDelegate<ViewState>,
    private val eventDelegate: EventDelegate<ViewEvent>,
) : ViewModel(),
    StateViewModel<AssetTransferConfirmViewModel.ViewState> by stateDelegate,
    EventViewModel<AssetTransferConfirmViewModel.ViewEvent> by eventDelegate {
    private var senderAddress: String = ""
    private var receiverAddress: String = ""
    private var assetId: Long = -7L
    private var assetName: String = ""
    private var assetLogoUrl: String = ""
    private var assetBalance: String? = null
    private var transferAmount: String = ""
    private var transferNote: String = ""
    private var currentFee: String = "0.001"
    private var isAssetValid: Boolean = true

    init {
        stateDelegate.setDefaultState(ViewState.Loading)
        viewModelScope.launch {
            transactionSignManager.transactionManagerResultStateFlow.collect {
                when (it) {
                    is TransactionManagerResult.Error.GlobalWarningError.Api -> {
                        eventDelegate.sendEvent(ViewEvent.ShowError("API error occurred"))
                        restoreContentState()
                    }

                    is TransactionManagerResult.Error.GlobalWarningError.Defined -> {
                        eventDelegate.sendEvent(
                            ViewEvent.ShowError(
                                it.error,
                            ),
                        )
                        restoreContentState()
                    }

                    is TransactionManagerResult.Error.GlobalWarningError.MinBalanceError -> {
                        eventDelegate.sendEvent(ViewEvent.ShowError("Insufficient balance for minimum requirements"))
                        restoreContentState()
                    }

                    is TransactionManagerResult.LedgerOperationCanceled -> {
                        eventDelegate.sendEvent(ViewEvent.ShowError("Ledger operation cancelled"))
                        restoreContentState()
                    }

                    is TransactionManagerResult.LedgerScanFailed -> {
                        eventDelegate.sendEvent(ViewEvent.ShowError("Ledger scan failed"))
                        restoreContentState()
                    }

                    is TransactionManagerResult.LedgerWaitingForApproval -> {}
                    is TransactionManagerResult.Loading -> {
                        stateDelegate.updateState { ViewState.Confirming }
                    }

                    is TransactionManagerResult.Success -> {
                        println(it.signedTransactionDetail.toString())
                        sendSignedTransaction(it.signedTransactionDetail)
                    }

                    null -> {}
                }
            }
        }
    }

    // Setter methods
    fun setSenderAddress(address: String) {
        senderAddress = address
        updateContentState()
        fetchAccountBalance(address)
        calculateMinimumFee(address)
    }

    fun setReceiverAddress(address: String) {
        receiverAddress = address
        updateContentState()
    }

    fun setAssetId(asset: Long) {
        assetId = asset
        if (assetId > 0) {
            fetchAssetDetail(assetId)
            fetchAccountASABalance(assetId)
        } else {
            assetName = ""
            updateContentState()
        }
    }

    private fun fetchAssetDetail(id: Long) {
        viewModelScope.launch {
            isAssetValid = true
            when (val result = getAssetDetailApiService.getAssetDetail(id)) {
                is ApiResult.Success -> {
                    val assetDetail = result.data
                    assetName = assetDetail.fullName ?: assetDetail.shortName ?: "Unknown Asset"
                    assetLogoUrl = assetDetail.logoUri ?: ""
                    isAssetValid = true
                    updateContentState()
                }

                is ApiResult.Error -> {
                    assetName = "Asset $id"
                    assetLogoUrl = ""
                    isAssetValid = false
                    transactionSignManager.stopAllResources()
                    eventDelegate.sendEvent(ViewEvent.UnrecognizedAsset("Asset $id not found"))
                    updateContentState()
                }

                is ApiResult.NetworkError -> {
                    assetName = "Asset $id"
                    assetLogoUrl = ""
                    isAssetValid = false
                    transactionSignManager.stopAllResources()
                    eventDelegate.sendEvent(ViewEvent.UnrecognizedAsset("Unable to verify asset. Please check your connection."))
                    updateContentState()
                }
            }
        }
    }

    private fun fetchAccountASABalance(assetId: Long) {
        viewModelScope.launch {
            try {
                val balance = getAccountASABalance(senderAddress, assetId)
                assetBalance = balance?.toString()
                updateContentState()
                println("Fetched ASA balance: ${balance?.toString() ?: "null"}")
            } catch (e: Exception) {
                println("Exception fetching ASA balance: ${e.message}")
                assetBalance = null
                updateContentState()
            }
        }
    }

    fun setAmount(amount: String) {
        transferAmount = amount
        updateContentState()
    }

    fun setNote(note: String) {
        transferNote = note
        updateContentState()
    }

    private fun updateContentState() {
        val currentState = stateDelegate.state.value
        val currentBalance =
            if (currentState is ViewState.Content) {
                currentState.accountBalance
            } else {
                null
            }

        stateDelegate.updateState {
            ViewState.Content(
                senderAddress = senderAddress,
                receiverAddress = receiverAddress,
                amount = transferAmount,
                accountBalance = currentBalance,
                note = transferNote,
                fee = currentFee,
                assetId = assetId,
                assetName = assetName,
                assetLogoUrl = assetLogoUrl,
                assetBalance = assetBalance,
                isAssetValid = isAssetValid,
            )
        }
    }

    private fun fetchAccountBalance(address: String) {
        viewModelScope.launch {
            try {
                val balance = getAccountAlgoBalance(address)
                val currentState = stateDelegate.state.value
                if (currentState is ViewState.Content) {
                    stateDelegate.updateState {
                        currentState.copy(accountBalance = balance?.toString() ?: "0")
                    }
                }
                println("Fetched account balance: ${balance?.toString() ?: "0"}")
            } catch (e: Exception) {
                println("Exception fetching account balance: ${e.message}")
                val currentState = stateDelegate.state.value
                if (currentState is ViewState.Content) {
                    stateDelegate.updateState {
                        currentState.copy(accountBalance = "0")
                    }
                }
            }
        }
    }

    private fun restoreContentState() {
        stateDelegate.updateState {
            ViewState.Content(
                senderAddress = senderAddress,
                receiverAddress = receiverAddress,
                amount = transferAmount,
                accountBalance = (it as? ViewState.Content)?.accountBalance,
                fee = currentFee,
                assetId = assetId,
                assetName = assetName,
                assetLogoUrl = assetLogoUrl,
                assetBalance = assetBalance,
                isAssetValid = isAssetValid,
            )
        }
    }

    suspend fun createSendTransactionData(): TransactionSignData.Send? {
        // Validate and convert amount string to microAlgos (1 ALGO = 1,000,000 microAlgos)
        val amountBigInteger =
            try {
                BigInteger.parseString(transferAmount)
            } catch (e: Exception) {
                eventDelegate.sendEvent(ViewEvent.ShowError("Invalid amount format: $transferAmount"))
                restoreContentState()
                return null
            }

        if (amountBigInteger <= BigInteger.ZERO) {
            eventDelegate.sendEvent(ViewEvent.ShowError("Amount must be greater than 0"))
            restoreContentState()
            return null
        }

        // Fetch the sender's actual balance
        val senderAlgoAmount =
            try {
                getAccountAlgoBalance(senderAddress) ?: run {
                    eventDelegate.sendEvent(ViewEvent.ShowError("Unable to fetch sender account balance"))
                    restoreContentState()
                    return null
                }
            } catch (e: Exception) {
                eventDelegate.sendEvent(ViewEvent.ShowError("Error fetching balance: ${e.message}"))
                restoreContentState()
                return null
            }

        // Fetch the sender's minimum balance
        val minimumBalance =
            try {
                getAccountMinimumBalance(senderAddress) ?: run {
                    eventDelegate.sendEvent(ViewEvent.ShowError("Unable to fetch minimum balance"))
                    restoreContentState()
                    return null
                }
            } catch (e: Exception) {
                eventDelegate.sendEvent(ViewEvent.ShowError("Error fetching minimum balance: ${e.message}"))
                restoreContentState()
                return null
            }

        val amountInMicroAlgos = amountBigInteger

        // Determine if this is a max transaction by comparing amount with balance
        val isMaxTransaction = amountInMicroAlgos == senderAlgoAmount

        // Calculate fee based on account type (minimum 0.001 ALGO, 0.004 for Falcon24)
        val feeInAlgos = BigDecimal.parseString(currentFee)
        val fee = (feeInAlgos * BigDecimal.fromInt(1000000)).toBigInteger()

        // Validate balance based on whether this is a max transaction
        if (isMaxTransaction) {
            // For max transactions, ensure sender has enough for fee + minimum balance
            val requiredForMax = fee + minimumBalance.toBigInteger()
            if (senderAlgoAmount < requiredForMax) {
                val requiredInAlgos =
                    BigDecimal.parseString(requiredForMax.toString()) / BigDecimal.fromInt(1000000)
                eventDelegate.sendEvent(
                    ViewEvent.ShowError(
                        "Insufficient balance. You need at least ${requiredInAlgos.toStringExpanded()} ALGO for fee and minimum balance.",
                    ),
                )
                restoreContentState()
                return null
            }
        } else {
            // For non-max transactions, ensure sender has enough for amount + fee + minimum balance
            val totalRequired = amountInMicroAlgos + fee + minimumBalance.toBigInteger()
            if (senderAlgoAmount < totalRequired) {
                val availableInMicroAlgos = (senderAlgoAmount - minimumBalance.toBigInteger() - fee)
                val availableInMicroAlgosBigDecimal =
                    BigDecimal.parseString(availableInMicroAlgos.toString())
                val availableToSend = availableInMicroAlgosBigDecimal / BigDecimal.fromInt(1000000)
                eventDelegate.sendEvent(
                    ViewEvent.ShowError(
                        "Insufficient balance. Available to send: ${availableToSend.toStringExpanded()} ALGO",
                    ),
                )
                restoreContentState()
                return null
            }
        }

        return TransactionSignData.Send(
            senderAccountAddress = senderAddress,
            senderAuthAddress = null,
            senderAccountName = "",
            senderAlgoAmount = senderAlgoAmount,
            minimumBalance = minimumBalance,
            amount = amountInMicroAlgos,
            assetId = assetId,
            note = transferNote,
            targetUser =
                TargetUser(
                    publicKey = receiverAddress,
                ),
            signer = getTransactionSigner(senderAddress),
            isArc59Transaction = false,
            isMax = isMaxTransaction,
        )
    }

    fun setup(lifecycle: Lifecycle) {
        transactionSignManager.setup(lifecycle)
    }

    fun reset() {
        senderAddress = ""
        receiverAddress = ""
        assetId = -7L
        assetName = ""
        assetLogoUrl = ""
        assetBalance = null
        transferAmount = ""
        transferNote = ""
        currentFee = "0.001"
        isAssetValid = true
        stateDelegate.updateState { ViewState.Loading }
        transactionSignManager.stopAllResources()
    }

    fun sendTransaction() {
        viewModelScope.launch {
            val transactionData = createSendTransactionData()
            if (transactionData == null) {
                return@launch
            }
            stateDelegate.updateState { ViewState.Confirming }
            transactionSignManager.initSigningTransactions(
                isGroupTransaction = false,
                transactionData,
            )
        }
    }

    private fun sendSignedTransaction(signedTransactionDetail: SignedTransactionDetail) {
        viewModelScope.launch(Dispatchers.IO) {
            sendSignedTransactionUseCase
                .sendSignedTransaction(signedTransactionDetail = signedTransactionDetail)
                .collectLatest {
                    it.useSuspended(
                        onSuccess = { transactionId ->
                            eventDelegate.sendEvent(ViewEvent.TransactionSuccess(transactionId))
                            println("SendSignedTransaction onSuccess: $transactionId")
                        },
                        onFailed = { error ->
                            val errorMsg = error.exception?.message ?: "Transaction failed"
                            println("sendSignedTransaction Failed: $errorMsg")
                            // Check for duplicate transaction in ledger
                            if (errorMsg.contains("ledger", ignoreCase = true) ||
                                errorMsg.contains("duplicate", ignoreCase = true)
                            ) {
                                transactionSignManager.stopAllResources()
                                eventDelegate.sendEvent(
                                    ViewEvent.TransactionAlreadyInLedger(
                                        "Transaction error. Please try again.",
                                    ),
                                )
                            } else {
                                eventDelegate.sendEvent(ViewEvent.ShowError(errorMsg))
                                restoreContentState()
                            }
                        },
                    )
                }
        }
    }

    fun calculateMinimumFee(address: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val transactionFee = getTransactionFeeForAccount(address)
            currentFee = transactionFee.feeInAlgos
            updateContentState()
        }
    }

    override fun onCleared() {
        super.onCleared()
        reset()
    }

    sealed interface ViewState {
        data object Loading : ViewState

        data object Confirming : ViewState

        data class Content(
            val senderAddress: String,
            val receiverAddress: String,
            val amount: String,
            val accountBalance: String?,
            val note: String = "",
            val fee: String = "",
            val assetId: Long = -7L,
            val assetName: String = "",
            val assetLogoUrl: String = "",
            val assetBalance: String? = null,
            val isAssetValid: Boolean = true,
        ) : ViewState

        data class Error(
            val message: String,
        ) : ViewState
    }

    sealed interface ViewEvent {
        data class ShowError(
            val message: String,
            val id: String =
                Random
                    .nextLong()
                    .toString(),
        ) : ViewEvent

        data class UnrecognizedAsset(
            val message: String,
        ) : ViewEvent

        data class TransactionAlreadyInLedger(
            val message: String,
        ) : ViewEvent

        data class TransactionSuccess(
            val transactionId: String,
        ) : ViewEvent
    }
}
