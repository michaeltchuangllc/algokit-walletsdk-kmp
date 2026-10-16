package com.michaeltchuang.walletsdk.seedvault.service.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateViewModel

/**
 * The statements that must all be acknowledged before a seed phrase is revealed. Same as the
 * wallet-sdk-ui PassphraseAcknowledgeScreen, worded for a seed phrase.
 */
enum class Acknowledgement {
    NOBODY_CAN_SEE_SCREEN,
    SHARING_LOSES_FUNDS,
    LOSING_PHRASE_LOSES_FUNDS,
    NOBODY_WILL_ASK,
}

class RevealSeedPhraseViewModel(
    private val stateDelegate: StateDelegate<ViewState>,
) : ViewModel(),
    StateViewModel<RevealSeedPhraseViewModel.ViewState> by stateDelegate {
    init {
        stateDelegate.setDefaultState(ViewState())
    }

    fun onAcknowledgementChanged(
        acknowledgement: Acknowledgement,
        isChecked: Boolean,
    ) {
        stateDelegate.updateState { state ->
            state.copy(
                checked = if (isChecked) state.checked + acknowledgement else state.checked - acknowledgement,
            )
        }
    }

    data class ViewState(
        val checked: Set<Acknowledgement> = emptySet(),
    ) {
        val canReveal: Boolean get() = checked.size == Acknowledgement.entries.size
    }
}
