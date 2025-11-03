package com.michaeltchuang.walletsdk.ui.onboarding.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.account.domain.model.core.AccountCreation
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.CreateWatchAccountUseCase
import com.michaeltchuang.walletsdk.core.algosdk.isValidAlgorandAddress
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateViewModel
import com.michaeltchuang.walletsdk.core.foundation.utils.CreationType
import com.michaeltchuang.walletsdk.core.foundation.utils.Log
import com.michaeltchuang.walletsdk.core.foundation.utils.manager.AccountCreationManager
import kotlinx.coroutines.launch

private const val TAG = "CreateWatchAccountViewModel"

class CreateWatchAccountViewModel(
    private val stateDelegate: StateDelegate<ViewState>,
    private val eventDelegate: EventDelegate<ViewEvent>,
) : ViewModel(),
    StateViewModel<CreateWatchAccountViewModel.ViewState> by stateDelegate,
    EventViewModel<CreateWatchAccountViewModel.ViewEvent> by eventDelegate {

    init {
        stateDelegate.setDefaultState(ViewState.Idle)
    }

    fun onAddressChanged(newAddress: String) {
        val trimmedAddress = newAddress.trim()
        val isValid = isValidAlgorandAddress(trimmedAddress)

        stateDelegate.updateState { currentState ->
            when (currentState) {
                is ViewState.Content -> currentState.copy(
                    address = trimmedAddress,
                    isAddressValid = isValid
                )

                else -> ViewState.Content(
                    address = trimmedAddress,
                    isAddressValid = isValid
                )
            }
        }
    }

    fun createWatchAccount() {
        viewModelScope.launch {
            try {
                stateDelegate.updateState { currentState ->
                    when (currentState) {
                        is ViewState.Content -> currentState.copy(isLoading = true)
                        else -> ViewState.Content(isLoading = true)
                    }
                }

                val currentState = stateDelegate.state.value
                val address = if (currentState is ViewState.Content) currentState.address else ""

                if (isValidAlgorandAddress(address).not()) {
                    displayError("Invalid Algorand address")
                    return@launch
                }

                Log.d(TAG, "Creating watch account for address: $address")

                //  val result = createWatchAccountUseCase(address)

                /*    result.fold(
                        onSuccess = {
                            Log.d(TAG, "Watch account created successfully")
                            eventDelegate.sendEvent(ViewEvent.WatchAccountCreated)
                        },
                        onFailure = { exception ->
                            Log.e(TAG, "Failed to create watch account: ${exception.message}")
                            displayError(exception.message ?: "Failed to create watch account")
                        }
                    )*/
                val accountCreation =
                    AccountCreation(
                        address = address,
                        customName = null,
                        isBackedUp = false,
                        type = AccountCreation.Type.NoAuth,
                        creationType = CreationType.CREATE,
                    )

                AccountCreationManager.storePendingAccountCreation(accountCreation)
                eventDelegate.sendEvent(ViewEvent.WatchAccountCreated(accountCreation))

            } catch (e: Exception) {
                Log.e(TAG, "Error creating watch account: ${e.message}")
                displayError("Failed to create watch account: ${e.message}")
            } finally {
                stateDelegate.updateState { currentState ->
                    when (currentState) {
                        is ViewState.Content -> currentState.copy(isLoading = false)
                        else -> ViewState.Content(isLoading = false)
                    }
                }
            }
        }
    }

    private suspend fun displayError(message: String) {
        eventDelegate.sendEvent(ViewEvent.Error(message))
    }


    sealed interface ViewState {
        data object Idle : ViewState

        data object Loading : ViewState

        data class Content(
            val address: String = "",
            val isAddressValid: Boolean = false,
            val isLoading: Boolean = false,
        ) : ViewState
    }

    sealed interface ViewEvent {
        data class Error(val message: String) : ViewEvent
        data class WatchAccountCreated(val accountCreation: AccountCreation) : ViewEvent
    }
}