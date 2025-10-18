package com.michaeltchuang.walletsdk.ui.settings.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.account.data.mapper.entity.Algo25AccountTypeMapper
import com.michaeltchuang.walletsdk.core.account.domain.model.core.AccountCreation
import com.michaeltchuang.walletsdk.core.account.domain.repository.local.HdSeedRepository
import com.michaeltchuang.walletsdk.core.algosdk.createAlgo25Account
import com.michaeltchuang.walletsdk.core.encryption.initializeEncryptionManager
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateViewModel
import com.michaeltchuang.walletsdk.core.foundation.utils.CreationType
import com.michaeltchuang.walletsdk.core.foundation.utils.manager.AccountCreationManager
import kotlinx.coroutines.launch

class DeveloperSettingsViewModel(
    private val algo25AccountTypeMapper: Algo25AccountTypeMapper,
    private val hdSeedRepository: HdSeedRepository,
    private val stateDelegate: StateDelegate<ViewState>,
    private val eventDelegate: EventDelegate<ViewEvent>,
) : ViewModel(),
    StateViewModel<DeveloperSettingsViewModel.ViewState> by stateDelegate,
    EventViewModel<DeveloperSettingsViewModel.ViewEvent> by eventDelegate {
    init {
        stateDelegate.setDefaultState(ViewState.Loading)
        viewModelScope.launch { initializeEncryptionManager() }
        hasAnySeedExist()
    }

    private fun hasAnySeedExist() {
        viewModelScope.launch {
            hdSeedRepository.hasAnySeed().let { hasAnySeed ->
                stateDelegate.updateState {
                    ViewState.Content(hasAnySeed)
                }
            }
        }
    }

    fun createAlgoAccount() {
        viewModelScope.launch {
            try {
                createAlgo25Account()?.let {
                    val accountCreation =
                        AccountCreation(
                            address = it.address,
                            customName = null,
                            isBackedUp = false,
                            type = algo25AccountTypeMapper(it.secretKey),
                            creationType = CreationType.CREATE,
                        )
                    // Store the account creation data in the manager
                    AccountCreationManager.storePendingAccountCreation(accountCreation = accountCreation)
                    eventDelegate.sendEvent(ViewEvent.AccountCreated(accountCreation = accountCreation))
                } ?: run {
                    displayError("Failed to create account")
                }
            } catch (e: Exception) {
                displayError(e.message ?: "Unknown error")
            }
        }
    }

    private fun displayError(message: String) {
        viewModelScope.launch {
            eventDelegate.sendEvent(ViewEvent.Error(message))
        }
    }

    sealed interface ViewState {
        data object Idle : ViewState

        data object Loading : ViewState

        data class Content(
            val hasAnySeed: Boolean,
        ) : ViewState
    }

    sealed interface ViewEvent {
        data class AccountCreated(
            val accountCreation: AccountCreation,
        ) : ViewEvent

        data class Error(
            val message: String,
        ) : ViewEvent
    }
}
