package com.michaeltchuang.walletsdk.seedvault.service.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateViewModel
import com.michaeltchuang.walletsdk.seedvault.service.domain.model.VaultSeed
import com.michaeltchuang.walletsdk.seedvault.service.domain.usecase.AuthorizeApp
import com.michaeltchuang.walletsdk.seedvault.service.domain.usecase.GetSeeds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Asks the user which seeds the app that launched the authorization screen may use. Nothing is
 * granted until [onAllow]; seeds the app already has are listed as authorized and cannot be picked.
 */
class AuthorizeAppViewModel(
    private val appPackageName: String,
    private val getSeeds: GetSeeds,
    private val authorizeApp: AuthorizeApp,
    private val appLabel: String,
    private val stateDelegate: StateDelegate<ViewState>,
    private val eventDelegate: EventDelegate<ViewEvent>,
) : ViewModel(),
    StateViewModel<AuthorizeAppViewModel.ViewState> by stateDelegate,
    EventViewModel<AuthorizeAppViewModel.ViewEvent> by eventDelegate {
    init {
        stateDelegate.setDefaultState(ViewState.Loading)
        viewModelScope.launch {
            val seeds = getSeeds().first()
            stateDelegate.updateState {
                ViewState.Content(
                    appLabel = appLabel,
                    seeds = seeds.map { SeedChoice(it, isAuthorized = it.authorizedApps.any { app -> app.packageName == appPackageName }) },
                )
            }
        }
    }

    fun onToggleSeed(seedId: Long) {
        stateDelegate.onState<ViewState.Content> { content ->
            val seed = content.seeds.firstOrNull { it.seed.id == seedId } ?: return@onState
            if (seed.isAuthorized || content.isSaving) return@onState
            val selected = if (seedId in content.selectedSeedIds) content.selectedSeedIds - seedId else content.selectedSeedIds + seedId
            stateDelegate.updateState { content.copy(selectedSeedIds = selected) }
        }
    }

    fun onAllow() {
        stateDelegate.onState<ViewState.Content> { content ->
            if (!content.canAllow) return@onState
            stateDelegate.updateState { content.copy(isSaving = true) }
            viewModelScope.launch {
                runCatching {
                    content.selectedSeedIds.forEach { seedId -> authorizeApp(seedId, appPackageName, appLabel) }
                }.onSuccess { eventDelegate.sendEvent(ViewEvent.Authorized) }
                    .onFailure {
                        stateDelegate.updateState { content.copy(isSaving = false) }
                        eventDelegate.sendEvent(ViewEvent.ShowError)
                    }
            }
        }
    }

    data class SeedChoice(
        val seed: VaultSeed,
        val isAuthorized: Boolean,
    )

    sealed interface ViewState {
        data object Loading : ViewState

        data class Content(
            val appLabel: String,
            val seeds: List<SeedChoice>,
            val selectedSeedIds: Set<Long> = emptySet(),
            val isSaving: Boolean = false,
        ) : ViewState {
            val canAllow: Boolean get() = selectedSeedIds.isNotEmpty() && !isSaving
        }
    }

    sealed interface ViewEvent {
        data object Authorized : ViewEvent

        data object ShowError : ViewEvent
    }
}
