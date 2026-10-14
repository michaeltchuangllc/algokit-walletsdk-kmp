package com.michaeltchuang.walletsdk.seedvault.demo.data

import com.michaeltchuang.walletsdk.seedvault.demo.data.model.VaultSeed
import kotlinx.coroutines.flow.Flow

/**
 * Source of the seeds shown by the Seed Vault demo screens.
 *
 * [FakeSeedVaultDataSource] keeps everything in memory so the UI can be worked on without a
 * Trusty applet. A real implementation (e.g. backed by the seed-vault service over AIDL) can
 * replace it later without touching the screens.
 */
interface SeedVaultDataSource {
    val seeds: Flow<List<VaultSeed>>

    suspend fun addSeed(): VaultSeed

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
