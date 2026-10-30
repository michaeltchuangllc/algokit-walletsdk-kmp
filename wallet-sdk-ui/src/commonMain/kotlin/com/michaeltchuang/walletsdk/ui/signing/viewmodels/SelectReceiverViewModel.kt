package com.michaeltchuang.walletsdk.ui.signing.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.account.domain.model.custom.AccountLite
import com.michaeltchuang.walletsdk.core.account.domain.usecase.core.NameRegistrationUseCase
import com.michaeltchuang.walletsdk.core.algosdk.isValidAlgorandAddress
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateViewModel
import kotlinx.coroutines.launch

import com.michaeltchuang.walletsdk.core.foundation.utils.Chain

class SelectReceiverViewModel(
    private val nameRegistrationUseCase: NameRegistrationUseCase,
    private val stateDelegate: StateDelegate<ViewState>,
    private val eventDelegate: EventDelegate<ViewEvent>,
) : ViewModel(),
    StateViewModel<SelectReceiverViewModel.ViewState> by stateDelegate,
    EventViewModel<SelectReceiverViewModel.ViewEvent> by eventDelegate {
    private var senderAddress: String = ""
    private var chainId: String = ""
    private var clipboardText: String = ""
    private var allAccounts: List<AccountLite> = emptyList()

    init {
        stateDelegate.setDefaultState(ViewState.Loading)
    }

    fun setup(
        senderAddress: String,
        chainId: String = "",
    ) {
        this.senderAddress = senderAddress
        this.chainId = chainId
        loadAccounts()
    }

    fun setSearchText(text: String) {
        val currentState = stateDelegate.state.value
        if (currentState is ViewState.Content) {
            // Filter accounts based on search text
            val filteredAccounts =
                if (text.isEmpty()) {
                    allAccounts
                } else {
                    allAccounts.filter { account ->
                        account.customName.contains(text, ignoreCase = true) || account.address.contains(text, ignoreCase = true)
                    }
                }

            stateDelegate.updateState {
                currentState.copy(
                    searchText = text,
                    accounts = filteredAccounts,
                )
            }
        }
    }

    fun setClipboardText(text: String) {
        if (text.isNotEmpty() && isValidAlgorandAddress(text)) {
            clipboardText = text
            val currentState = stateDelegate.state.value
            if (currentState is ViewState.Content) {
                stateDelegate.updateState {
                    currentState.copy(clipboardText = text)
                }
            }
        }
    }

    fun onClipboardAddressTapped(text: String) {
        val currentState = stateDelegate.state.value
        if (currentState is ViewState.Content) {
            stateDelegate.updateState {
                currentState.copy(searchText = text)
            }
        }
    }

    fun onAccountSelected(receiverAddress: String) {
        if (receiverAddress != senderAddress) {
            viewModelScope.launch {
                eventDelegate.sendEvent(
                    ViewEvent.NavigateToAssetTransfer(
                        senderAddress,
                        receiverAddress,
                    ),
                )
            }
        }
    }

    fun onNextPressed() {
        val currentState = stateDelegate.state.value
        if (currentState is ViewState.Content && currentState.searchText.isNotEmpty()) {
            viewModelScope.launch {
                eventDelegate.sendEvent(
                    ViewEvent.NavigateToAssetTransfer(
                        senderAddress,
                        currentState.searchText,
                    ),
                )
            }
        }
    }

    private fun loadAccounts() {
        viewModelScope.launch {
            try {
                stateDelegate.updateState { ViewState.Loading }
                // Filter out the sender account and filter by chainId if provided
                val accounts =
                    nameRegistrationUseCase.getAccountLite().filter { it.address != senderAddress }
                val filteredAccounts = filterAccountsByChainId(accounts, chainId)

                // Store all accounts for searching/filtering
                allAccounts = filteredAccounts

                stateDelegate.updateState {
                    ViewState.Content(
                        accounts = filteredAccounts,
                        searchText = "",
                        clipboardText = clipboardText,
                    )
                }
            } catch (e: Exception) {
                stateDelegate.updateState {
                    ViewState.Error("Failed to load accounts: ${e.message}")
                }
            }
        }
    }

    private fun filterAccountsByChainId(
        accounts: List<AccountLite>,
        chainId: String,
    ): List<AccountLite> =
        chainId.takeIf { it.isNotBlank() }?.let { id ->
            val targetChainId = Chain.fromId(id)?.id ?: id
            accounts.filter { it.chainId == targetChainId }
        } ?: accounts

    sealed interface ViewState {
        data object Loading : ViewState

        data class Content(
            val accounts: List<AccountLite>,
            val searchText: String,
            val clipboardText: String = "",
        ) : ViewState

        data class Error(
            val message: String,
        ) : ViewState
    }

    sealed interface ViewEvent {
        data class NavigateToAssetTransfer(
            val senderAddress: String,
            val receiverAddress: String,
        ) : ViewEvent
    }
}
