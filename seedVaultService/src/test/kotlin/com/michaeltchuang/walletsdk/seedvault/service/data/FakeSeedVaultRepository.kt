package com.michaeltchuang.walletsdk.seedvault.service.data

import com.michaeltchuang.walletsdk.seedvault.service.domain.model.VaultSeed
import com.michaeltchuang.walletsdk.seedvault.service.domain.repository.SeedVaultRepository
import com.michaeltchuang.walletsdk.seedvault.service.ui.preview.SeedVaultPreviewData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * In-memory [SeedVaultRepository]. Nothing is persisted or encrypted, and the sample seeds share
 * [SeedVaultPreviewData.seedPhrase].
 */
class FakeSeedVaultRepository(
    initialSeeds: List<VaultSeed> = SeedVaultPreviewData.seeds,
) : SeedVaultRepository {
    private val seedsState = MutableStateFlow(initialSeeds)
    private val seedPhrases = initialSeeds.associate { it.id to SeedVaultPreviewData.seedPhrase }.toMutableMap()

    // Ids are never reused, even after a seed is deleted
    private var nextId = (initialSeeds.maxOfOrNull { it.id } ?: 0L) + 1

    /** When set, [addSeed] and [getSeedPhrase] throw it, e.g. to mimic a Keystore failure. */
    var failure: Exception? = null

    val seeds: List<VaultSeed> get() = seedsState.value

    override fun getSeeds(): Flow<List<VaultSeed>> = seedsState.asStateFlow()

    override fun getSeed(seedId: Long): Flow<VaultSeed?> = seedsState.map { seeds -> seeds.firstOrNull { it.id == seedId } }

    override suspend fun addSeed(
        seedPhrase: List<String>,
        isBackedUp: Boolean,
    ): VaultSeed {
        failure?.let { throw it }
        val id = nextId++
        val seed = VaultSeed(id = id, name = "Demo Seed $id", isBackedUp = isBackedUp, authorizedApps = emptyList())
        seedPhrases[id] = seedPhrase
        seedsState.update { it + seed }
        return seed
    }

    override suspend fun getSeedPhrase(seedId: Long): List<String>? {
        failure?.let { throw it }
        return seedPhrases[seedId]
    }

    override suspend fun renameSeed(
        seedId: Long,
        name: String,
    ) {
        updateSeed(seedId) { it.copy(name = name) }
    }

    override suspend fun deleteSeed(seedId: Long) {
        seedPhrases.remove(seedId)
        seedsState.update { seeds -> seeds.filterNot { it.id == seedId } }
    }

    override suspend fun revokeApp(
        seedId: Long,
        packageName: String,
    ) {
        updateSeed(seedId) { seed ->
            seed.copy(authorizedApps = seed.authorizedApps.filterNot { it.packageName == packageName })
        }
    }

    private fun updateSeed(
        seedId: Long,
        transform: (VaultSeed) -> VaultSeed,
    ) {
        seedsState.update { seeds -> seeds.map { if (it.id == seedId) transform(it) else it } }
    }
}
