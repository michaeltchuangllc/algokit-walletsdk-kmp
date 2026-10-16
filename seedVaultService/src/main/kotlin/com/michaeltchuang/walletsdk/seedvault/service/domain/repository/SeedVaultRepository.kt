package com.michaeltchuang.walletsdk.seedvault.service.domain.repository

import com.michaeltchuang.walletsdk.seedvault.service.domain.model.VaultSeed
import kotlinx.coroutines.flow.Flow

/**
 * Storage for the seeds in the vault. Seed phrases go in and come out as plain words; how they
 * are encoded and encrypted at rest is up to the implementation.
 */
interface SeedVaultRepository {
    fun getSeeds(): Flow<List<VaultSeed>>

    fun getSeed(seedId: Long): Flow<VaultSeed?>

    suspend fun addSeed(
        seedPhrase: List<String>,
        isBackedUp: Boolean,
    ): VaultSeed

    /** Returns the decrypted seed phrase, or null if the seed no longer exists. */
    suspend fun getSeedPhrase(seedId: Long): List<String>?

    suspend fun renameSeed(
        seedId: Long,
        name: String,
    )

    suspend fun deleteSeed(seedId: Long)

    suspend fun revokeApp(
        seedId: Long,
        packageName: String,
    )
}
