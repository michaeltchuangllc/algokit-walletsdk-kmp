package com.michaeltchuang.walletsdk.ui.onboarding.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.account.domain.model.core.AccountCreation
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.AccountAlreadyExistsException
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.ValidateWatchAccountUseCase
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
    private val validateWatchAccountUseCase: ValidateWatchAccountUseCase,
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
                is ViewState.Content ->
                    currentState.copy(
                        address = trimmedAddress,
                        isAddressValid = isValid,
                    )

                else ->
                    ViewState.Content(
                        address = trimmedAddress,
                        isAddressValid = isValid,
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
                    displayError(ErrorType.InvalidAddress)
                    return@launch
                }

                Log.d(TAG, "Validating watch account for address: $address")

                // Validate the account doesn't already exist before navigating
                val validationResult = validateWatchAccountUseCase(address)

                validationResult.fold(
                    onSuccess = {
                        Log.d(TAG, "Watch account validation successful")

                        // Now that validation passed, store for the name screen
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
                    },
                    onFailure = { exception ->
                        Log.e(TAG, "Validation failed: ${exception.message}")
                        val errorType =
                            when (exception) {
                                is AccountAlreadyExistsException -> ErrorType.AccountAlreadyExists
                                else -> ErrorType.ValidationFailed
                            }
                        displayError(errorType)
                    },
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error validating watch account: ${e.message}")
                displayError(ErrorType.ValidationFailed)
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

    private suspend fun displayError(errorType: ErrorType) {
        eventDelegate.sendEvent(ViewEvent.Error(errorType))
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
        data class Error(
            val errorType: ErrorType,
        ) : ViewEvent

        data class WatchAccountCreated(
            val accountCreation: AccountCreation,
        ) : ViewEvent
    }

    enum class ErrorType {
        InvalidAddress,
        AccountAlreadyExists,
        ValidationFailed,
    }
}
