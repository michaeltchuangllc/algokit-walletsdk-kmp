package com.michaeltchuang.walletsdk.seedvault.demo.data

import com.michaeltchuang.walletsdk.seedvault.demo.data.model.AuthorizedApp
import com.michaeltchuang.walletsdk.seedvault.demo.data.model.VaultSeed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory [SeedVaultDataSource] with fake seeds. Nothing is persisted and no keys are created.
 */
class FakeSeedVaultDataSource(
    initialSeeds: List<VaultSeed> = sampleSeeds,
) : SeedVaultDataSource {
    private val seedsState = MutableStateFlow(initialSeeds)

    // Ids are never reused, even after a seed is deleted
    private var nextId = (initialSeeds.maxOfOrNull { it.id } ?: 0L) + 1

    override val seeds: Flow<List<VaultSeed>> = seedsState.asStateFlow()

    override suspend fun addSeed(): VaultSeed {
        val id = nextId++
        val seed =
            VaultSeed(
                id = id,
                name = "Demo Seed $id",
                isBackedUp = false,
                authorizedApps = emptyList(),
            )
        seedsState.update { it + seed }
        return seed
    }

    override suspend fun renameSeed(
        seedId: Long,
        name: String,
    ) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        updateSeed(seedId) { it.copy(name = trimmed) }
    }

    override suspend fun deleteSeed(seedId: Long) {
        seedsState.update { seeds -> seeds.filterNot { it.id == seedId } }
    }

    override suspend fun revokeApp(
        seedId: Long,
        packageName: String,
    ) {
        updateSeed(seedId) { seed ->
            seed.copy(
                authorizedApps =
                    seed.authorizedApps.filterNot {
                        it.packageName == packageName && !it.isPrivileged
                    },
            )
        }
    }

    private fun updateSeed(
        seedId: Long,
        transform: (VaultSeed) -> VaultSeed,
    ) {
        seedsState.update { seeds ->
            seeds.map { if (it.id == seedId) transform(it) else it }
        }
    }

    companion object {
        private val walletDemoApp =
            AuthorizedApp(
                packageName = "com.michaeltchuang.walletsdk.demo",
                label = "AlgoKit Wallet SDK Demo",
            )
        private val walletServiceDemoApp =
            AuthorizedApp(
                packageName = "com.michaeltchuang.walletsdk.service.demo",
                label = "Wallet Service Demo",
            )
        private val systemWalletApp =
            AuthorizedApp(
                packageName = "com.example.systemwallet",
                label = "System Wallet",
                isPrivileged = true,
            )

        /** Fake seeds used by the app and by the Compose previews. */
        val sampleSeeds =
            listOf(
                VaultSeed(
                    id = 1,
                    name = "Demo Seed 1",
                    isBackedUp = true,
                    authorizedApps = listOf(walletDemoApp),
                ),
                VaultSeed(
                    id = 2,
                    name = "Demo Seed 2",
                    isBackedUp = false,
                    authorizedApps = listOf(walletDemoApp, walletServiceDemoApp, systemWalletApp),
                ),
            )
    }
}
