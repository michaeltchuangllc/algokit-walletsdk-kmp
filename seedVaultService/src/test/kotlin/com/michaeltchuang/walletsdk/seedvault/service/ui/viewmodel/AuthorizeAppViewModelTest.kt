package com.michaeltchuang.walletsdk.seedvault.service.ui.viewmodel

import com.michaeltchuang.walletsdk.seedvault.service.data.FakeSeedVaultRepository
import com.michaeltchuang.walletsdk.seedvault.service.domain.usecase.AuthorizeApp
import com.michaeltchuang.walletsdk.seedvault.service.domain.usecase.GetSeeds
import com.michaeltchuang.walletsdk.seedvault.service.ui.viewmodel.AuthorizeAppViewModel.ViewEvent
import com.michaeltchuang.walletsdk.seedvault.service.ui.viewmodel.AuthorizeAppViewModel.ViewState
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorizeAppViewModelTest : ViewModelTest() {
    private val repository = FakeSeedVaultRepository()

    private fun viewModel(appPackageName: String = CLIENT_PACKAGE) =
        AuthorizeAppViewModel(
            appPackageName = appPackageName,
            appLabel = "Client",
            getSeeds = GetSeeds(repository::getSeeds),
            authorizeApp = AuthorizeApp(repository::authorizeApp),
            stateDelegate = stateDelegate(),
            eventDelegate = eventDelegate(),
        )

    private fun AuthorizeAppViewModel.content() = state.value as ViewState.Content

    @Test
    fun listsSeedsWithNothingSelected() =
        runTest {
            val viewModel = viewModel()
            assertEquals(ViewState.Loading, viewModel.state.value)

            advanceUntilIdle()

            assertEquals(2, viewModel.content().seeds.size)
            assertTrue(viewModel.content().selectedSeedIds.isEmpty())
            assertFalse(viewModel.content().canAllow)
        }

    @Test
    fun marksSeedsTheAppAlreadyHas() =
        runTest {
            repository.authorizeApp(1L, CLIENT_PACKAGE, "Client")
            val viewModel = viewModel()
            advanceUntilIdle()

            assertTrue(
                viewModel
                    .content()
                    .seeds
                    .first { it.seed.id == 1L }
                    .isAuthorized,
            )
            assertFalse(
                viewModel
                    .content()
                    .seeds
                    .first { it.seed.id == 2L }
                    .isAuthorized,
            )
        }

    @Test
    fun toggleSelectsAndDeselects() =
        runTest {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.onToggleSeed(1L)
            assertEquals(setOf(1L), viewModel.content().selectedSeedIds)
            assertTrue(viewModel.content().canAllow)

            viewModel.onToggleSeed(1L)
            assertTrue(viewModel.content().selectedSeedIds.isEmpty())
        }

    @Test
    fun alreadyAuthorizedSeedCannotBeToggled() =
        runTest {
            repository.authorizeApp(1L, CLIENT_PACKAGE, "Client")
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.onToggleSeed(1L)

            assertTrue(viewModel.content().selectedSeedIds.isEmpty())
        }

    @Test
    fun allowAuthorizesSelectedSeedsOnly() =
        runTest {
            val viewModel = viewModel()
            val events = collectEvents(viewModel.viewEvent)
            advanceUntilIdle()

            viewModel.onToggleSeed(2L)
            viewModel.onAllow()
            advanceUntilIdle()

            assertTrue(
                repository.seeds
                    .first { it.id == 2L }
                    .authorizedApps
                    .any { it.packageName == CLIENT_PACKAGE && it.label == "Client" },
            )
            assertTrue(
                repository.seeds
                    .first { it.id == 1L }
                    .authorizedApps
                    .none { it.packageName == CLIENT_PACKAGE },
            )
            assertEquals(listOf(ViewEvent.Authorized), events)
        }

    @Test
    fun allowDoesNothingWithoutSelection() =
        runTest {
            val viewModel = viewModel()
            val events = collectEvents(viewModel.viewEvent)
            advanceUntilIdle()

            viewModel.onAllow()
            advanceUntilIdle()

            assertTrue(events.isEmpty())
        }

    @Test
    fun allowFailureShowsErrorAndAllowsRetry() =
        runTest {
            val viewModel = viewModel()
            val events = collectEvents(viewModel.viewEvent)
            advanceUntilIdle()
            viewModel.onToggleSeed(1L)
            repository.failure = IllegalStateException("Database closed")

            viewModel.onAllow()
            advanceUntilIdle()

            assertEquals(listOf(ViewEvent.ShowError), events)
            assertTrue(viewModel.content().canAllow)
        }

    private companion object {
        const val CLIENT_PACKAGE = "com.example.client"
    }
}
