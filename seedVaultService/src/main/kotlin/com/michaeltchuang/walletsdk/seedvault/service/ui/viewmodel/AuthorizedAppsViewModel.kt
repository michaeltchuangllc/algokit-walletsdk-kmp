package com.michaeltchuang.walletsdk.seedvault.service.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateViewModel
import com.michaeltchuang.walletsdk.seedvault.service.domain.model.AuthorizedApp
import com.michaeltchuang.walletsdk.seedvault.service.domain.usecase.GetSeed
import com.michaeltchuang.walletsdk.seedvault.service.domain.usecase.RevokeAuthorizedApp
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

class AuthorizedAppsViewModel(
    private val seedId: Long,
    private val getSeed: GetSeed,
    private val revokeAuthorizedApp: RevokeAuthorizedApp,
    private val stateDelegate: StateDelegate<ViewState>,
) : ViewModel(),
    StateViewModel<AuthorizedAppsViewModel.ViewState> by stateDelegate {
    init {
        stateDelegate.setDefaultState(ViewState.Loading)
        viewModelScope.launch {
            getSeed(seedId).filterNotNull().collect { seed ->
                stateDelegate.updateState { ViewState.Content(seed.authorizedApps) }
            }
        }
    }

    fun onRevoke(app: AuthorizedApp) {
        viewModelScope.launch { revokeAuthorizedApp(seedId, app.packageName) }
    }

    sealed interface ViewState {
        data object Loading : ViewState

        data class Content(
            val apps: List<AuthorizedApp>,
        ) : ViewState
    }
}
