package com.michaeltchuang.walletsdk.ui.signing.viewmodels

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetLocalAccount
import com.michaeltchuang.walletsdk.core.deeplink.model.KeyRegTransactionDetail
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateViewModel
import com.michaeltchuang.walletsdk.core.foundation.utils.MIN_FEE
import com.michaeltchuang.walletsdk.core.foundation.utils.Result
import com.michaeltchuang.walletsdk.core.foundation.utils.formatAmount
import com.michaeltchuang.walletsdk.core.transaction.domain.usecase.CreateKeyRegTransaction
import com.michaeltchuang.walletsdk.core.transaction.domain.usecase.GetTransactionParams
import com.michaeltchuang.walletsdk.core.transaction.domain.usecase.SendSignedTransactionUseCase
import com.michaeltchuang.walletsdk.core.transaction.model.KeyRegTransaction
import com.michaeltchuang.walletsdk.core.transaction.model.SignedTransactionDetail
import com.michaeltchuang.walletsdk.core.transaction.signmanager.ExternalTransactionSignResult
import com.michaeltchuang.walletsdk.core.transaction.signmanager.KeyRegTransactionSignManager
import com.michaeltchuang.walletsdk.core.transaction.signmanager.PendingTransactionRequestManger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class KeyRegConfirmViewModel(
    private val sendSignedTransactionUseCase: SendSignedTransactionUseCase,
    private val createKeyRegTransaction: CreateKeyRegTransaction,
    private val keyRegTransactionSignManager: KeyRegTransactionSignManager,
    private val getLocalAccount: GetLocalAccount,
    private val getTransactionParams: GetTransactionParams,
    private val stateDelegate: StateDelegate<ViewState>,
    private val eventDelegate: EventDelegate<ViewEvent>,
) : ViewModel(),
    StateViewModel<KeyRegConfirmViewModel.ViewState> by stateDelegate,
    EventViewModel<KeyRegConfirmViewModel.ViewEvent> by eventDelegate {
    private val minimumFee = MIN_FEE.toString().formatAmount()

    init {
        stateDelegate.setDefaultState(ViewState.Content(minimumFee = minimumFee))
        viewModelScope.launch {
            keyRegTransactionSignManager.keyRegTransactionSignResultFlow.collect {
                when (it) {
                    is ExternalTransactionSignResult.Success<*> -> {
                        sendSignedTransaction(it.signedTransaction)
                    }

                    is ExternalTransactionSignResult.Error -> {
                        transactionFailed(it.getMessage())
                        keyRegTransactionSignManager.manualStopAllResources()
                    }

                    is ExternalTransactionSignResult.TransactionCancelled -> {
                        transactionFailed(it.error.getMessage())
                        keyRegTransactionSignManager.manualStopAllResources()
                    }

                    is ExternalTransactionSignResult.NotInitialized,
                    is ExternalTransactionSignResult.Loading,
                    -> {
                        // Ignore these states - no action needed
                    }

                    else -> {
                        println("confirmTransaction: Unhandled state $it")
                    }
                }
            }
        }
    }

    fun setup(lifecycle: Lifecycle) {
        keyRegTransactionSignManager.setup(lifecycle)
    }

    fun getPendingTransactionRequest(): KeyRegTransactionDetail? = PendingTransactionRequestManger.getPendingTransactionRequest()

    fun confirmTransaction() {
        getPendingTransactionRequest()?.let {
            stateDelegate.updateState {
                ViewState.Loading
            }
            viewModelScope.launch(Dispatchers.IO) {
                createKeyRegTransaction(it).use(
                    onSuccess = { transaction ->
                        signKeyRegTransaction(transaction)
                    },
                    onFailed = { exception, _ ->
                        transactionFailed(exception.message ?: "Unknown error")
                    },
                )
            }
        } ?: run {
            transactionFailed("No pending transaction request found")
        }
    }

    fun calculateMinimumFee(txnDetail: KeyRegTransactionDetail?) {
        viewModelScope.launch {
            val fee =
                withContext(Dispatchers.IO) {
                    txnDetail?.fee?.toLongOrNull()?.takeIf { it > 0L }?.let {
                        return@withContext it
                    }

                    val accountAddress = txnDetail?.address?.trim() ?: return@withContext null
                    val account = getLocalAccount.invoke(accountAddress)
                    val paramsResult = getTransactionParams()
                    if (paramsResult !is Result.Success) return@withContext null

                    val minimumFee = (paramsResult.data.minFee ?: MIN_FEE).coerceAtLeast(MIN_FEE)
                    if (account is LocalAccount.Falcon24) {
                        minimumFee * FALCON_BUNDLE_TXN_COUNT
                    } else {
                        minimumFee
                    }
                } ?: return@launch
            updateMinimumFee(fee.toString().formatAmount())
        }
    }

    private fun updateMinimumFee(fee: String) {
        stateDelegate.updateState { currentState ->
            when (currentState) {
                is ViewState.Content -> ViewState.Content(minimumFee = fee)
                is ViewState.Loading -> ViewState.Loading
            }
        }
    }

    fun signKeyRegTransaction(keyRegTransaction: KeyRegTransaction) {
        keyRegTransactionSignManager.signKeyRegTransaction(keyRegTransaction)
    }

    fun sendSignedTransaction(signedTransactions: List<Any?>) {
        viewModelScope.launch(Dispatchers.IO) {
            val signedTxnByteArray = signedTransactions.first() as? ByteArray ?: return@launch
            val signedTransactionDetail =
                SignedTransactionDetail.ExternalTransaction(signedTxnByteArray)
            sendSignedTransactionUseCase
                .sendSignedTransaction(signedTransactionDetail)
                .collectLatest {
                    it.useSuspended(
                        onSuccess = {
                            eventDelegate.sendEvent(ViewEvent.SendSignedTransactionSuccess(it))
                            PendingTransactionRequestManger.clearPendingTransactionRequest()
                            keyRegTransactionSignManager.manualStopAllResources()
                            println("SendSignedTransaction onSuccess: $it")
                        },
                        onFailed = {
                            println("sendSignedTransaction Failed: ${it.exception?.message}")
                            transactionFailed(it.exception?.message ?: "Unknown error")
                        },
                    )
                }
        }
    }

    private fun transactionFailed(error: String) {
        stateDelegate.updateState { ViewState.Content(minimumFee = minimumFee) }
        viewModelScope.launch {
            eventDelegate.sendEvent(ViewEvent.SendSignedTransactionFailed(error))
        }
        println("confirmTransaction Failed: $error")
    }

    private companion object {
        const val FALCON_BUNDLE_TXN_COUNT = 4L
    }

    sealed interface ViewState {
        data object Loading : ViewState

        data class Content(
            val minimumFee: String,
        ) : ViewState
    }

    sealed interface ViewEvent {
        data class SendSignedTransactionSuccess(
            val transactionId: String,
        ) : ViewEvent

        data class SendSignedTransactionFailed(
            val error: String,
        ) : ViewEvent
    }
}
