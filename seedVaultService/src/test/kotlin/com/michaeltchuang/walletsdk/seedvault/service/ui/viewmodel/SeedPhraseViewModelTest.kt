package com.michaeltchuang.walletsdk.seedvault.service.ui.viewmodel

import com.michaeltchuang.walletsdk.seedvault.service.data.FakeSeedVaultRepository
import com.michaeltchuang.walletsdk.seedvault.service.domain.usecase.GetSeed
import com.michaeltchuang.walletsdk.seedvault.service.domain.usecase.GetSeedPhrase
import com.michaeltchuang.walletsdk.seedvault.service.ui.preview.SeedVaultPreviewData
import com.michaeltchuang.walletsdk.seedvault.service.ui.viewmodel.SeedPhraseViewModel.ViewEvent
import com.michaeltchuang.walletsdk.seedvault.service.ui.viewmodel.SeedPhraseViewModel.ViewState
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SeedPhraseViewModelTest : ViewModelTest() {
    private val repository = FakeSeedVaultRepository()

    private fun viewModel(seedId: Long) =
        SeedPhraseViewModel(
            seedId = seedId,
            getSeed = GetSeed(repository::getSeed),
            getSeedPhrase = GetSeedPhrase(repository::getSeedPhrase),
            stateDelegate = stateDelegate(),
            eventDelegate = eventDelegate(),
        )

    @Test
    fun showsSeedPhrase() =
        runTest {
            val viewModel = viewModel(seedId = 1)

            advanceUntilIdle()

            assertEquals(ViewState.Content("Demo Seed 1", SeedVaultPreviewData.seedPhrase), viewModel.state.value)
        }

    @Test
    fun missingSeedShowsError() =
        runTest {
            val viewModel = viewModel(seedId = 42)
            val events = collectEvents(viewModel.viewEvent)

            advanceUntilIdle()

            assertEquals(listOf(ViewEvent.ShowError), events)
            assertEquals(ViewState.Loading, viewModel.state.value)
        }

    @Test
    fun decryptionFailureShowsError() =
        runTest {
            repository.failure = IllegalStateException("Keystore unavailable")
            val viewModel = viewModel(seedId = 1)
            val events = collectEvents(viewModel.viewEvent)

            advanceUntilIdle()

            assertEquals(listOf(ViewEvent.ShowError), events)
        }
}
