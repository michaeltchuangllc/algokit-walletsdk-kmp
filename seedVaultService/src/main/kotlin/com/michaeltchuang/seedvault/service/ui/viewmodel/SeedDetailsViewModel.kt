package com.michaeltchuang.seedvault.service.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.seedvault.service.domain.model.VaultSeed
import com.michaeltchuang.seedvault.service.domain.usecase.DeleteSeed
import com.michaeltchuang.seedvault.service.domain.usecase.GetSeed
import com.michaeltchuang.seedvault.service.domain.usecase.RenameSeed
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateViewModel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

class SeedDetailsViewModel(
    private val seedId: Long,
    private val getSeed: GetSeed,
    private val renameSeed: RenameSeed,
    private val deleteSeed: DeleteSeed,
    private val stateDelegate: StateDelegate<ViewState>,
    private val eventDelegate: EventDelegate<ViewEvent>,
) : ViewModel(),
    StateViewModel<SeedDetailsViewModel.ViewState> by stateDelegate,
    EventViewModel<SeedDetailsViewModel.ViewEvent> by eventDelegate {
    init {
        stateDelegate.setDefaultState(ViewState.Loading)
        viewModelScope.launch {
            // A deleted seed is handled by ViewEvent.SeedDeleted, so missing seeds are ignored here
            getSeed(seedId).filterNotNull().collect { seed ->
                stateDelegate.updateState { ViewState.Content(seed) }
            }
        }
    }

    fun onRename(name: String) {
        viewModelScope.launch { renameSeed(seedId, name) }
    }

    fun onDelete() {
        viewModelScope.launch {
            deleteSeed(seedId)
            eventDelegate.sendEvent(ViewEvent.SeedDeleted)
        }
    }

    sealed interface ViewState {
        data object Loading : ViewState

        data class Content(
            val seed: VaultSeed,
        ) : ViewState
    }

    sealed interface ViewEvent {
        data object SeedDeleted : ViewEvent
    }
}
