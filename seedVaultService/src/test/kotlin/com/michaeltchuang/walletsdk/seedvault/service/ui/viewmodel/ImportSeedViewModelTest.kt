package com.michaeltchuang.walletsdk.seedvault.service.ui.viewmodel

import com.michaeltchuang.walletsdk.seedvault.service.data.FakeSeedVaultRepository
import com.michaeltchuang.walletsdk.seedvault.service.domain.usecase.AddSeed
import com.michaeltchuang.walletsdk.seedvault.service.domain.usecase.ValidateSeedPhrase
import com.michaeltchuang.walletsdk.seedvault.service.ui.preview.SeedVaultPreviewData
import com.michaeltchuang.walletsdk.seedvault.service.ui.viewmodel.ImportSeedViewModel.ViewEvent
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportSeedViewModelTest : ViewModelTest() {
    private val repository = FakeSeedVaultRepository()
    private val words = SeedVaultPreviewData.seedPhrase

    private fun viewModel(isValid: Boolean = true) =
        ImportSeedViewModel(
            validateSeedPhrase = ValidateSeedPhrase { isValid },
            addSeed = AddSeed(repository::addSeed),
            stateDelegate = stateDelegate(),
            eventDelegate = eventDelegate(),
        )

    @Test
    fun startsWithEmptyWordsAndCannotImport() {
        val state = viewModel().state.value

        assertEquals(25, state.words.size)
        assertFalse(state.canImport)
    }

    @Test
    fun pasteFillsAllWords() {
        val viewModel = viewModel()

        viewModel.onPaste("  ${words.joinToString("\n").uppercase()}  ")

        assertEquals(words, viewModel.state.value.words)
        assertTrue(viewModel.state.value.canImport)
    }

    @Test
    fun pasteOfWrongWordCountShowsErrorAndKeepsWords() =
        runTest {
            val viewModel = viewModel()
            val events = collectEvents(viewModel.viewEvent)

            viewModel.onPaste(words.take(24).joinToString(" "))
            advanceUntilIdle()

            assertEquals(listOf(ViewEvent.InvalidClipboard), events)
            assertFalse(viewModel.state.value.canImport)
        }

    @Test
    fun pastingWholePhraseIntoOneFieldFillsAllWords() {
        val viewModel = viewModel()

        viewModel.onWordChange(3, words.joinToString(" "))

        assertEquals(words, viewModel.state.value.words)
    }

    @Test
    fun typingSetsASingleWord() {
        val viewModel = viewModel()

        viewModel.onWordChange(1, " Abandon ")

        assertEquals("abandon", viewModel.state.value.words[1])
        assertEquals("", viewModel.state.value.words[0])
    }

    @Test
    fun importStoresSeedAsBackedUp() =
        runTest {
            val viewModel = viewModel()
            val events = collectEvents(viewModel.viewEvent)
            viewModel.onPaste(words.joinToString(" "))

            viewModel.onImport()
            advanceUntilIdle()

            val added = repository.seeds.last()
            assertTrue(added.isBackedUp)
            assertEquals(words, repository.getSeedPhrase(added.id))
            assertEquals(listOf(ViewEvent.SeedSaved), events)
        }

    @Test
    fun importOfInvalidPhraseShowsErrorAndStoresNothing() =
        runTest {
            val viewModel = viewModel(isValid = false)
            val events = collectEvents(viewModel.viewEvent)
            viewModel.onPaste(words.joinToString(" "))

            viewModel.onImport()
            advanceUntilIdle()

            assertEquals(listOf(ViewEvent.InvalidSeedPhrase), events)
            assertEquals(2, repository.seeds.size)
            assertTrue(viewModel.state.value.canImport)
        }

    @Test
    fun importFailureShowsErrorAndAllowsRetry() =
        runTest {
            val viewModel = viewModel()
            val events = collectEvents(viewModel.viewEvent)
            viewModel.onPaste(words.joinToString(" "))
            repository.failure = IllegalStateException("Keystore unavailable")

            viewModel.onImport()
            advanceUntilIdle()

            assertEquals(listOf(ViewEvent.ShowError), events)
            assertTrue(viewModel.state.value.canImport)
        }

    @Test
    fun importDoesNothingUntilAllWordsAreEntered() =
        runTest {
            val viewModel = viewModel()
            val events = collectEvents(viewModel.viewEvent)

            viewModel.onImport()
            advanceUntilIdle()

            assertTrue(events.isEmpty())
            assertEquals(2, repository.seeds.size)
        }
}
