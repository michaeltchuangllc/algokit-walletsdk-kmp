package com.michaeltchuang.walletsdk.seedvault.service.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateViewModel
import com.michaeltchuang.walletsdk.seedvault.service.domain.usecase.GetSeed
import com.michaeltchuang.walletsdk.seedvault.service.domain.usecase.GetSeedPhrase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Decrypts the seed phrase of one seed. Scoped to the "View seed phrase" destination, so the
 * words are dropped as soon as that screen is left.
 */
class SeedPhraseViewModel(
    private val seedId: Long,
    private val getSeed: GetSeed,
    private val getSeedPhrase: GetSeedPhrase,
    private val stateDelegate: StateDelegate<ViewState>,
    private val eventDelegate: EventDelegate<ViewEvent>,
) : ViewModel(),
    StateViewModel<SeedPhraseViewModel.ViewState> by stateDelegate,
    EventViewModel<SeedPhraseViewModel.ViewEvent> by eventDelegate {
    init {
        stateDelegate.setDefaultState(ViewState.Loading)
        viewModelScope.launch {
            runCatching {
                val seed = getSeed(seedId).first()
                val seedPhrase = getSeedPhrase(seedId)
                if (seed != null && seedPhrase != null) ViewState.Content(seed.name, seedPhrase) else null
            }.onSuccess { content ->
                if (content != null) {
                    stateDelegate.updateState { content }
                } else {
                    eventDelegate.sendEvent(ViewEvent.ShowError)
                }
            }.onFailure { eventDelegate.sendEvent(ViewEvent.ShowError) }
        }
    }

    sealed interface ViewState {
        data object Loading : ViewState

        data class Content(
            val seedName: String,
            val seedPhrase: List<String>,
        ) : ViewState
    }

    sealed interface ViewEvent {
        data object ShowError : ViewEvent
    }
}
