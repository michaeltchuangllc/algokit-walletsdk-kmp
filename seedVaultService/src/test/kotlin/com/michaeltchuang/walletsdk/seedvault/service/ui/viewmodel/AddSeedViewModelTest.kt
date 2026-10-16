package com.michaeltchuang.walletsdk.seedvault.service.ui.viewmodel

import com.michaeltchuang.walletsdk.seedvault.service.data.FakeSeedVaultRepository
import com.michaeltchuang.walletsdk.seedvault.service.domain.usecase.AddSeed
import com.michaeltchuang.walletsdk.seedvault.service.domain.usecase.GenerateSeedPhrase
import com.michaeltchuang.walletsdk.seedvault.service.ui.preview.SeedVaultPreviewData
import com.michaeltchuang.walletsdk.seedvault.service.ui.viewmodel.AddSeedViewModel.ViewEvent
import com.michaeltchuang.walletsdk.seedvault.service.ui.viewmodel.AddSeedViewModel.ViewState
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AddSeedViewModelTest : ViewModelTest() {
    private val repository = FakeSeedVaultRepository()

    private fun viewModel(generateSeedPhrase: GenerateSeedPhrase = GenerateSeedPhrase { SeedVaultPreviewData.seedPhrase }) =
        AddSeedViewModel(
            generateSeedPhrase = generateSeedPhrase,
            addSeed = AddSeed(repository::addSeed),
            stateDelegate = stateDelegate(),
            eventDelegate = eventDelegate(),
        )

    @Test
    fun showsGeneratedSeedPhrase() =
        runTest {
            val viewModel = viewModel()
            assertEquals(ViewState.Loading, viewModel.state.value)

            advanceUntilIdle()

            assertEquals(ViewState.Content(SeedVaultPreviewData.seedPhrase), viewModel.state.value)
        }

    @Test
    fun saveStoresSeedPhraseWithBackupState() =
        runTest {
            val viewModel = viewModel()
            val events = collectEvents(viewModel.viewEvent)
            advanceUntilIdle()

            viewModel.onSave(isBackedUp = true)
            advanceUntilIdle()

            val added = repository.seeds.last()
            assertEquals(3L, added.id)
            assertTrue(added.isBackedUp)
            assertEquals(SeedVaultPreviewData.seedPhrase, repository.getSeedPhrase(added.id))
            assertEquals(listOf(ViewEvent.SeedSaved), events)
        }

    @Test
    fun saveOnlyStoresOnce() =
        runTest {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.onSave(isBackedUp = false)
            viewModel.onSave(isBackedUp = false)
            advanceUntilIdle()

            assertEquals(3, repository.seeds.size)
        }

    @Test
    fun saveFailureShowsErrorAndAllowsRetry() =
        runTest {
            val viewModel = viewModel()
            val events = collectEvents(viewModel.viewEvent)
            advanceUntilIdle()
            repository.failure = IllegalStateException("Keystore unavailable")

            viewModel.onSave(isBackedUp = true)
            advanceUntilIdle()

            assertEquals(listOf(ViewEvent.ShowError), events)
            assertEquals(ViewState.Content(SeedVaultPreviewData.seedPhrase, isSaving = false), viewModel.state.value)
            assertEquals(2, repository.seeds.size)
        }

    @Test
    fun generateFailureShowsError() =
        runTest {
            val viewModel = viewModel(generateSeedPhrase = { error("No entropy") })
            val events = collectEvents(viewModel.viewEvent)

            advanceUntilIdle()

            assertEquals(listOf(ViewEvent.ShowError), events)
            assertEquals(ViewState.Loading, viewModel.state.value)
        }
}
