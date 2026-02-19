package com.michaeltchuang.walletsdk.ui.signing.viewmodels

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetTransactionFeeForAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetTransactionSigner
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateViewModel
import com.michaeltchuang.walletsdk.core.network.service.AssetDetailApiService
import com.michaeltchuang.walletsdk.core.transaction.domain.usecase.SendSignedTransactionUseCase
import com.michaeltchuang.walletsdk.core.transaction.model.SignedTransactionDetail
import com.michaeltchuang.walletsdk.core.transaction.model.TransactionManagerResult
import com.michaeltchuang.walletsdk.core.transaction.model.TransactionSignData
import com.michaeltchuang.walletsdk.core.transaction.signmanager.TransactionSignManager
import com.michaeltchuang.walletsdk.utils.DataResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AddAssetViewModel(
    private val getAssetDetailApiService: AssetDetailApiService,
    private val getTransactionFeeForAccount: GetTransactionFeeForAccount,
    private val transactionSignManager: TransactionSignManager,
    private val sendSignedTransactionUseCase: SendSignedTransactionUseCase,
    private val getTransactionSigner: GetTransactionSigner,
    private val stateDelegate: StateDelegate<ViewState>,
    private val eventDelegate: EventDelegate<ViewEvent>,
) : ViewModel(),
    StateViewModel<AddAssetViewModel.ViewState> by stateDelegate,
    EventViewModel<AddAssetViewModel.ViewEvent> by eventDelegate {

    private var assetData = AssetData()

    init {
        stateDelegate.setDefaultState(ViewState.Loading)
        collectTransactionResults()
    }

    fun setup(lifecycle: Lifecycle) {
        transactionSignManager.setup(lifecycle)
    }

    private fun collectTransactionResults() {
        viewModelScope.launch {
            transactionSignManager.transactionManagerResultStateFlow.collectLatest { result ->
                when (result) {
                    is TransactionManagerResult.Success -> {
                        sendSignedTransaction(result.signedTransactionDetail)
                    }
                    is TransactionManagerResult.Error -> {
                        eventDelegate.sendEvent(
                            ViewEvent.ShowError(result.toString()),
                        )
                        restoreContentState()
                    }
                    else -> { /* Handle other states */ }
                }
            }
        }
    }

    private suspend fun sendSignedTransaction(signedTransactionDetail: SignedTransactionDetail) {
        sendSignedTransactionUseCase.sendSignedTransaction(signedTransactionDetail).collect { result ->
            when (result) {
                is DataResource.Success -> {
                    val txId = result.data ?: extractTransactionId(signedTransactionDetail)
                    eventDelegate.sendEvent(ViewEvent.AssetOptInSuccess(txId))
                    restoreContentState()
                }
                is DataResource.Error -> {
                    eventDelegate.sendEvent(
                        ViewEvent.ShowError(result.exception?.message ?: "Failed to send transaction"),
                    )
                    restoreContentState()
                }
                is DataResource.Loading -> {
                    // Already in confirming state, no action needed
                }
            }
        }
    }

    private fun extractTransactionId(signedTransactionDetail: SignedTransactionDetail): String =
        when (signedTransactionDetail) {
            is SignedTransactionDetail.AssetOperation.AssetAddition -> "${signedTransactionDetail.senderAccountAddress}_${signedTransactionDetail.assetId}"
            else -> "unknown"
        }

    fun fetchAssetDetail(id: String) {
        viewModelScope.launch {
            stateDelegate.updateState { ViewState.Loading }
            when (val result = getAssetDetailApiService.getAssetDetail(id.toLong())) {
                is com.michaeltchuang.walletsdk.core.network.model.ApiResult.Success -> {
                    val assetDetail = result.data
                    assetData = assetData.copy(
                        assetId = id,
                        assetName = assetDetail.fullName ?: assetDetail.shortName ?: "Unknown Asset",
                        logoUri = assetDetail.logoUri,
                        isVerified = assetDetail.verificationTier == "verified",
                    )
                    updateContentState()
                }
                is com.michaeltchuang.walletsdk.core.network.model.ApiResult.Error -> {
                    stateDelegate.updateState {
                        ViewState.Error(result.message ?: "Failed to fetch asset details")
                    }
                }
                is com.michaeltchuang.walletsdk.core.network.model.ApiResult.NetworkError -> {
                    stateDelegate.updateState {
                        ViewState.Error(result.exception.message ?: "Network error occurred")
                    }
                }
            }
        }
    }

    fun setAccountAddress(address: String) {
        assetData = assetData.copy(accountAddress = address)
        stateDelegate.onState<ViewState.Content> { updateContentState() }
        calculateMinimumFee(address)
    }

    private fun updateContentState() {
        stateDelegate.updateState {
            ViewState.Content(
                assetId = assetData.assetId,
                assetName = assetData.assetName,
                logoUri = assetData.logoUri,
                accountAddress = assetData.accountAddress,
                fee = assetData.fee,
                isVerified = assetData.isVerified,
            )
        }
    }

    private fun restoreContentState() {
        updateContentState()
    }

    fun reset() {
        assetData = AssetData()
        stateDelegate.updateState { ViewState.Loading }
    }

    fun copyAssetId() {
        println("Copying asset ID: ${assetData.assetId}")
    }

    fun approveAssetOptIn() {
        viewModelScope.launch {
            stateDelegate.updateState { ViewState.Confirming }

            // Get the transaction signer for the account
            val signer = getTransactionSigner(assetData.accountAddress)

            // Create asset opt-in transaction data
            val addAssetTransactionData =
                TransactionSignData.AddAsset(
                    senderAccountAddress = assetData.accountAddress,
                    senderAuthAddress = null,
                    assetId = assetData.assetId.toLongOrNull() ?: 0L,
                    signer = signer,
                )

            // Initialize signing through TransactionSignManager
            transactionSignManager.initSigningTransactions(
                isGroupTransaction = false,
                addAssetTransactionData,
            )
        }
    }

    fun calculateMinimumFee(address: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val transactionFee = getTransactionFeeForAccount(address)
            assetData = assetData.copy(fee = transactionFee.feeInAlgos)
            stateDelegate.onState<ViewState.Content> { updateContentState() }
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
            val assetId: String,
            val assetName: String,
            val logoUri: String? = null,
            val accountAddress: String,
            val fee: String,
            val isVerified: Boolean = true,
        ) : ViewState

        data class Error(
            val message: String,
        ) : ViewState
    }

    sealed interface ViewEvent {
        data class ShowError(
            val message: String,
            val id: String =
                kotlin.random.Random
                    .nextLong()
                    .toString(),
        ) : ViewEvent

        data class AssetOptInSuccess(
            val transactionId: String,
        ) : ViewEvent
    }

    private data class AssetData(
        val assetId: String = "",
        val assetName: String = "",
        val logoUri: String? = null,
        val accountAddress: String = "",
        val fee: String = "0.001",
        val isVerified: Boolean = true,
    )
}
