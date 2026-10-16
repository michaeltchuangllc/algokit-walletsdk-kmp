package com.michaeltchuang.walletsdk.seedvault.service.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateViewModel
import com.michaeltchuang.walletsdk.seedvault.service.domain.model.VaultSeed
import com.michaeltchuang.walletsdk.seedvault.service.domain.usecase.GetSeeds
import kotlinx.coroutines.launch

class SeedVaultSettingsViewModel(
    private val getSeeds: GetSeeds,
    private val stateDelegate: StateDelegate<ViewState>,
) : ViewModel(),
    StateViewModel<SeedVaultSettingsViewModel.ViewState> by stateDelegate {
    init {
        stateDelegate.setDefaultState(ViewState.Loading)
        viewModelScope.launch {
            getSeeds().collect { seeds ->
                stateDelegate.updateState { ViewState.Content(seeds) }
            }
        }
    }

    sealed interface ViewState {
        data object Loading : ViewState

        data class Content(
            val seeds: List<VaultSeed>,
        ) : ViewState
    }
}
