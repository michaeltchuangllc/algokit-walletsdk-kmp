package com.michaeltchuang.walletsdk.seedvault.demo.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.michaeltchuang.walletsdk.seedvault.demo.data.FakeSeedVaultDataSource
import com.michaeltchuang.walletsdk.seedvault.demo.data.SeedVaultDataSource
import com.michaeltchuang.walletsdk.seedvault.demo.data.model.VaultSeed
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * UI state shared by all Seed Vault demo screens.
 */
data class SeedVaultUiState(
    val seeds: List<VaultSeed> = emptyList(),
) {
    fun seed(seedId: Long): VaultSeed? = seeds.firstOrNull { it.id == seedId }
}

/**
 * Stub ViewModel for the Seed Vault demo screens. It only forwards to a [SeedVaultDataSource],
 * so the fake data can be swapped for a real vault without changing the UI.
 */
class SeedVaultViewModel(
    private val dataSource: SeedVaultDataSource,
) : ViewModel() {
    val uiState: StateFlow<SeedVaultUiState> =
        dataSource.seeds
            .map { SeedVaultUiState(seeds = it) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, SeedVaultUiState())

    fun addSeed() {
        viewModelScope.launch { dataSource.addSeed() }
    }

    fun renameSeed(
        seedId: Long,
        name: String,
    ) {
        viewModelScope.launch { dataSource.renameSeed(seedId, name) }
    }

    fun deleteSeed(seedId: Long) {
        viewModelScope.launch { dataSource.deleteSeed(seedId) }
    }

    fun revokeApp(
        seedId: Long,
        packageName: String,
    ) {
        viewModelScope.launch { dataSource.revokeApp(seedId, packageName) }
    }

    companion object {
        /** Uses fake data for now. Swap in a real [SeedVaultDataSource] here once one exists. */
        val Factory: ViewModelProvider.Factory =
            viewModelFactory {
                initializer { SeedVaultViewModel(FakeSeedVaultDataSource()) }
            }
    }
}
