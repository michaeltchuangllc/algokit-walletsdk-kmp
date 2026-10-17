package com.michaeltchuang.seedvault.service.ui.viewmodel

import com.michaeltchuang.seedvault.service.data.FakeSeedVaultRepository
import com.michaeltchuang.seedvault.service.domain.usecase.GetSeed
import com.michaeltchuang.seedvault.service.domain.usecase.RevokeAuthorizedAppUseCase
import com.michaeltchuang.seedvault.service.ui.viewmodel.AuthorizedAppsViewModel.ViewState
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AuthorizedAppsViewModelTest : ViewModelTest() {
    private val repository = FakeSeedVaultRepository()
    private val viewModel by lazy {
        AuthorizedAppsViewModel(
            seedId = 2,
            getSeed = GetSeed(repository::getSeed),
            revokeAuthorizedApp = RevokeAuthorizedAppUseCase(repository),
            stateDelegate = stateDelegate(),
        )
    }

    private val apps get() = (viewModel.state.value as ViewState.Content).apps

    @Test
    fun revokeSkipsPrivilegedApps() =
        runTest {
            viewModel
            advanceUntilIdle()
            val initialApps = apps
            val privileged = initialApps.single { it.isPrivileged }
            val regular = initialApps.first { !it.isPrivileged }

            viewModel.onRevoke(regular)
            viewModel.onRevoke(privileged)
            advanceUntilIdle()

            assertEquals(initialApps - regular, apps)
        }
}
