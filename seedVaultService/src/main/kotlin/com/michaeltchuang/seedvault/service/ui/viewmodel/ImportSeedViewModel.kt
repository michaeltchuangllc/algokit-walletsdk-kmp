package com.michaeltchuang.seedvault.service.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.seedvault.service.domain.usecase.AddSeed
import com.michaeltchuang.seedvault.service.domain.usecase.ValidateSeedPhrase
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateViewModel
import kotlinx.coroutines.launch

/**
 * Collects a 25-word seed phrase typed in word by word or pasted from the clipboard, and stores it
 * as a backed up seed. Scoped to the "Import a seed" destination, so the words are dropped as soon
 * as that screen is left.
 */
class ImportSeedViewModel(
    private val validateSeedPhrase: ValidateSeedPhrase,
    private val addSeed: AddSeed,
    private val stateDelegate: StateDelegate<ViewState>,
    private val eventDelegate: EventDelegate<ViewEvent>,
) : ViewModel(),
    StateViewModel<ImportSeedViewModel.ViewState> by stateDelegate,
    EventViewModel<ImportSeedViewModel.ViewEvent> by eventDelegate {
    init {
        stateDelegate.setDefaultState(ViewState())
    }

    /** A value with several words in it was pasted into a field, so it is treated as the whole phrase. */
    fun onWordChange(
        index: Int,
        value: String,
    ) {
        if (value.toWords().size > 1) {
            onPaste(value)
            return
        }
        stateDelegate.updateState { state ->
            state.copy(words = state.words.mapIndexed { i, word -> if (i == index) value.trim().lowercase() else word })
        }
    }

    fun onPaste(text: String) {
        val words = text.toWords()
        if (words.size != WORD_COUNT) {
            viewModelScope.launch { eventDelegate.sendEvent(ViewEvent.InvalidClipboard) }
            return
        }
        stateDelegate.updateState { it.copy(words = words) }
    }

    fun onImport() {
        val state = stateDelegate.state.value
        if (!state.canImport) return
        stateDelegate.updateState { it.copy(isImporting = true) }
        viewModelScope.launch {
            if (!validateSeedPhrase(state.words)) {
                stateDelegate.updateState { it.copy(isImporting = false) }
                eventDelegate.sendEvent(ViewEvent.InvalidSeedPhrase)
                return@launch
            }
            // An imported phrase is one the user already has, so it counts as backed up
            runCatching { addSeed(state.words, true) }
                .onSuccess { eventDelegate.sendEvent(ViewEvent.SeedSaved) }
                .onFailure {
                    stateDelegate.updateState { it.copy(isImporting = false) }
                    eventDelegate.sendEvent(ViewEvent.ShowError)
                }
        }
    }

    data class ViewState(
        val words: List<String> = List(WORD_COUNT) { "" },
        val isImporting: Boolean = false,
    ) {
        val canImport: Boolean get() = !isImporting && words.all { it.isNotEmpty() }
    }

    sealed interface ViewEvent {
        data object SeedSaved : ViewEvent

        data object InvalidSeedPhrase : ViewEvent

        data object InvalidClipboard : ViewEvent

        data object ShowError : ViewEvent
    }

    // Phrases are copied with spaces, commas or line breaks between the words
    private fun String.toWords() = trim().lowercase().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }

    private companion object {
        const val WORD_COUNT = 25
    }
}
