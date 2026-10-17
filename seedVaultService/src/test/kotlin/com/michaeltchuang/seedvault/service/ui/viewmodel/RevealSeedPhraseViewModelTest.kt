package com.michaeltchuang.seedvault.service.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RevealSeedPhraseViewModelTest : ViewModelTest() {
    private val viewModel by lazy { RevealSeedPhraseViewModel(stateDelegate()) }

    @Test
    fun canRevealOnlyOnceEveryStatementIsAcknowledged() {
        Acknowledgement.entries.dropLast(1).forEach { viewModel.onAcknowledgementChanged(it, isChecked = true) }
        assertFalse(viewModel.state.value.canReveal)

        viewModel.onAcknowledgementChanged(Acknowledgement.entries.last(), isChecked = true)
        assertTrue(viewModel.state.value.canReveal)
    }

    @Test
    fun uncheckingDisablesReveal() {
        Acknowledgement.entries.forEach { viewModel.onAcknowledgementChanged(it, isChecked = true) }

        viewModel.onAcknowledgementChanged(Acknowledgement.NOBODY_WILL_ASK, isChecked = false)

        assertFalse(viewModel.state.value.canReveal)
        assertEquals(Acknowledgement.entries.toSet() - Acknowledgement.NOBODY_WILL_ASK, viewModel.state.value.checked)
    }
}
