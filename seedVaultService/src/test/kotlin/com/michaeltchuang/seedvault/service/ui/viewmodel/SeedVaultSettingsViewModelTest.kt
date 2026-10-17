package com.michaeltchuang.seedvault.service.ui.viewmodel

import com.michaeltchuang.seedvault.service.data.FakeSeedVaultRepository
import com.michaeltchuang.seedvault.service.domain.usecase.GetSeeds
import com.michaeltchuang.seedvault.service.ui.preview.SeedVaultPreviewData
import com.michaeltchuang.seedvault.service.ui.viewmodel.SeedVaultSettingsViewModel.ViewState
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SeedVaultSettingsViewModelTest : ViewModelTest() {
    private val repository = FakeSeedVaultRepository()

    private fun viewModel() = SeedVaultSettingsViewModel(GetSeeds(repository::getSeeds), stateDelegate())

    @Test
    fun startsLoadingThenShowsSeeds() =
        runTest {
            val viewModel = viewModel()
            assertEquals(ViewState.Loading, viewModel.state.value)

            advanceUntilIdle()

            assertEquals(ViewState.Content(SeedVaultPreviewData.seeds), viewModel.state.value)
        }

    @Test
    fun updatesWhenSeedsChange() =
        runTest {
            val viewModel = viewModel()
            advanceUntilIdle()

            repository.deleteSeed(seedId = 1)
            advanceUntilIdle()

            assertEquals(ViewState.Content(listOf(SeedVaultPreviewData.seeds.last())), viewModel.state.value)
        }
}
