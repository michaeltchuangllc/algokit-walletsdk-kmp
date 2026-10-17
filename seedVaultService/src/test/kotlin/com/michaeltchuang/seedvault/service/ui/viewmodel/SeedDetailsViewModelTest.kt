package com.michaeltchuang.seedvault.service.ui.viewmodel

import com.michaeltchuang.seedvault.service.data.FakeSeedVaultRepository
import com.michaeltchuang.seedvault.service.domain.usecase.DeleteSeed
import com.michaeltchuang.seedvault.service.domain.usecase.GetSeed
import com.michaeltchuang.seedvault.service.domain.usecase.RenameSeedUseCase
import com.michaeltchuang.seedvault.service.ui.viewmodel.SeedDetailsViewModel.ViewEvent
import com.michaeltchuang.seedvault.service.ui.viewmodel.SeedDetailsViewModel.ViewState
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SeedDetailsViewModelTest : ViewModelTest() {
    private val repository = FakeSeedVaultRepository()
    private val viewModel by lazy {
        SeedDetailsViewModel(
            seedId = 1,
            getSeed = GetSeed(repository::getSeed),
            renameSeed = RenameSeedUseCase(repository),
            deleteSeed = DeleteSeed(repository::deleteSeed),
            stateDelegate = stateDelegate(),
            eventDelegate = eventDelegate(),
        )
    }

    private val seedName get() = (viewModel.state.value as ViewState.Content).seed.name

    @Test
    fun renameTrimsAndIgnoresBlankNames() =
        runTest {
            viewModel.onRename("  Savings  ")
            advanceUntilIdle()
            assertEquals("Savings", seedName)

            viewModel.onRename("   ")
            advanceUntilIdle()
            assertEquals("Savings", seedName)
        }

    @Test
    fun deleteRemovesSeedAndSendsEvent() =
        runTest {
            val events = collectEvents(viewModel.viewEvent)

            viewModel.onDelete()
            advanceUntilIdle()

            assertEquals(listOf(2L), repository.seeds.map { it.id })
            assertEquals(listOf(ViewEvent.SeedDeleted), events)
        }
}
