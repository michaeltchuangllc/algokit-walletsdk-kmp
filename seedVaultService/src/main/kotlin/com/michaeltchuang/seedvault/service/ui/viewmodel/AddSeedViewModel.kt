package com.michaeltchuang.seedvault.service.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.seedvault.service.domain.usecase.AddSeed
import com.michaeltchuang.seedvault.service.domain.usecase.GenerateSeedPhrase
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateViewModel
import kotlinx.coroutines.launch

/**
 * Generates a new seed phrase and stores it once the user is done with it. Scoped to the
 * "Add a seed" destination, so the words are dropped as soon as that screen is left.
 */
class AddSeedViewModel(
    private val generateSeedPhrase: GenerateSeedPhrase,
    private val addSeed: AddSeed,
    private val stateDelegate: StateDelegate<ViewState>,
    private val eventDelegate: EventDelegate<ViewEvent>,
) : ViewModel(),
    StateViewModel<AddSeedViewModel.ViewState> by stateDelegate,
    EventViewModel<AddSeedViewModel.ViewEvent> by eventDelegate {
    init {
        stateDelegate.setDefaultState(ViewState.Loading)
        viewModelScope.launch {
            runCatching { generateSeedPhrase() }
                .onSuccess { seedPhrase -> stateDelegate.updateState { ViewState.Content(seedPhrase) } }
                .onFailure { eventDelegate.sendEvent(ViewEvent.ShowError) }
        }
    }

    fun onSave(isBackedUp: Boolean) {
        stateDelegate.onState<ViewState.Content> { content ->
            if (content.isSaving) return@onState
            stateDelegate.updateState { content.copy(isSaving = true) }
            viewModelScope.launch {
                runCatching { addSeed(content.seedPhrase, isBackedUp) }
                    .onSuccess { eventDelegate.sendEvent(ViewEvent.SeedSaved) }
                    .onFailure {
                        stateDelegate.updateState { content.copy(isSaving = false) }
                        eventDelegate.sendEvent(ViewEvent.ShowError)
                    }
            }
        }
    }

    sealed interface ViewState {
        data object Loading : ViewState

        data class Content(
            val seedPhrase: List<String>,
            val isSaving: Boolean = false,
        ) : ViewState
    }

    sealed interface ViewEvent {
        data object SeedSaved : ViewEvent

        data object ShowError : ViewEvent
    }
}
