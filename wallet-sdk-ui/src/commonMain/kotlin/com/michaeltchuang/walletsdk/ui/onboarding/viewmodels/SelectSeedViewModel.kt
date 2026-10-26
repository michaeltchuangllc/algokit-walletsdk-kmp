package com.michaeltchuang.walletsdk.ui.onboarding.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.account.domain.repository.solana.SeedVaultRepository
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAlgorandSeedVaultSeeds
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetSolanaAccountsFromSeedVaultUseCase
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch

class SelectSeedViewModel(
    private val stateDelegate: StateDelegate<ViewState>,
    private val eventDelegate: EventDelegate<ViewEvent>,
    private val getSolanaAccountsFromSeedVaultUseCase: GetSolanaAccountsFromSeedVaultUseCase,
    private val seedVaultRepository: SeedVaultRepository,
    private val getAlgorandSeedVaultSeeds: GetAlgorandSeedVaultSeeds,
) : ViewModel(),
    StateViewModel<SelectSeedViewModel.ViewState> by stateDelegate,
    EventViewModel<SelectSeedViewModel.ViewEvent> by eventDelegate {
    init {
        stateDelegate.setDefaultState(ViewState.Idle)
    }

    fun loadSeeds(accountType: String = "solana") {
        stateDelegate.updateState { ViewState.Loading }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (accountType.equals("algorand", ignoreCase = true)) {
                    setSeedItems(getAlgorandSeedVaultSeeds().map { SeedItem(id = it.id, name = it.name) })
                    return@launch
                }
                val seeds = getSolanaAccountsFromSeedVaultUseCase()

                if (seeds.isEmpty()) {
                    // Check if there are unauthorized seeds that can be authorized
                    val hasUnauthorizedSeeds = seedVaultRepository.hasUnauthorizedSeeds()
                    if (hasUnauthorizedSeeds) {
                        stateDelegate.updateState {
                            ViewState.NoAuthorizedSeeds(
                                message = "No authorized seeds found. Please authorize a seed from the Seed Vault.",
                                canAuthorize = true,
                            )
                        }
                    } else {
                        stateDelegate.updateState {
                            ViewState.NoAuthorizedSeeds(
                                message = "No seeds found. Please create a seed in the Seed Vault app first.",
                                canAuthorize = false,
                            )
                        }
                    }
                    return@launch
                }

                val seedItems =
                    seeds.map { seed ->
                        SeedItem(
                            id = seed.authToken.toString(),
                            name = seed.name,
                        )
                    }

                // Check if there are additional unauthorized seeds that could be authorized
                val hasUnauthorizedSeeds = seedVaultRepository.hasUnauthorizedSeeds()

                stateDelegate.updateState {
                    ViewState.Content(
                        seeds = seedItems,
                        hasMoreSeedsAvailable = hasUnauthorizedSeeds,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Check if this is a permission/security error
                val errorMessage =
                    when {
                        e.message?.contains("permission", ignoreCase = true) == true ->
                            "Permission denied to access Seed Vault. Please grant Seed Vault permissions in settings."
                        e.message?.contains("not available", ignoreCase = true) == true ->
                            "Seed Vault is not installed on this device."
                        e.message?.contains("security", ignoreCase = true) == true ->
                            "Security error accessing Seed Vault."
                        else -> "Failed to load seeds: ${e.message}"
                    }
                stateDelegate.updateState {
                    ViewState.Error(errorMessage)
                }
            }
        }
    }

    fun setSeedItems(
        seedItems: List<SeedItem>,
        hasMoreSeedsAvailable: Boolean = true,
    ) {
        if (seedItems.isEmpty()) {
            stateDelegate.updateState {
                ViewState.NoAuthorizedSeeds(
                    message = "No authorized seeds found. Please authorize a seed in Seed Vault.",
                    canAuthorize = true,
                )
            }
        } else {
            stateDelegate.updateState {
                ViewState.Content(
                    seeds = seedItems,
                    hasMoreSeedsAvailable = hasMoreSeedsAvailable,
                )
            }
        }
    }

    fun setError(message: String) {
        stateDelegate.updateState {
            ViewState.Error(message)
        }
    }

    fun setLoading() {
        stateDelegate.updateState {
            ViewState.Loading
        }
    }

    fun authorizeNewSeed() {
        viewModelScope.launch {
            // Emit event to request authorization - the screen will handle launching the intent
            eventDelegate.sendEvent(ViewEvent.RequestAuthorizeSeed)
        }
    }

    fun onSeedSelected(seed: SeedItem) {
        viewModelScope.launch {
            eventDelegate.sendEvent(ViewEvent.SeedSelected(seed))
        }
    }

    fun onAllSeedsConnected() {
        viewModelScope.launch {
            eventDelegate.sendEvent(ViewEvent.AllSeedsConnected)
        }
    }

    data class SeedItem(
        val id: String,
        val name: String,
    )

    sealed interface ViewState {
        data object Idle : ViewState

        data object Loading : ViewState

        data class Content(
            val seeds: List<SeedItem> = emptyList(),
            val hasMoreSeedsAvailable: Boolean = false,
        ) : ViewState

        data class NoAuthorizedSeeds(
            val message: String,
            val canAuthorize: Boolean = false,
        ) : ViewState

        data class Error(
            val message: String,
        ) : ViewState
    }

    sealed interface ViewEvent {
        data class SeedSelected(
            val seed: SeedItem,
        ) : ViewEvent

        data object AllSeedsConnected : ViewEvent

        data class Error(
            val message: String,
        ) : ViewEvent

        data object SeedAuthorized : ViewEvent

        data object RequestAuthorizeSeed : ViewEvent
    }
}
