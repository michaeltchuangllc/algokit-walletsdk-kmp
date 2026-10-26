package com.michaeltchuang.walletsdk.core.account.data.repository

import android.content.Context
import com.michaeltchuang.seedvault.sdk.client.SeedVaultServiceClient
import com.michaeltchuang.seedvault.sdk.domain.model.SeedVaultSeed
import com.michaeltchuang.walletsdk.core.account.domain.model.algorand.AlgorandSeedVaultSeed
import com.michaeltchuang.walletsdk.core.account.domain.model.local.SeedVaultAccount
import com.michaeltchuang.walletsdk.core.account.domain.repository.algorand.AlgorandSeedVaultRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal class AlgorandSeedVaultRepositoryImpl(
    context: Context,
    private val coroutineDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AlgorandSeedVaultRepository {
    private val applicationContext = context.applicationContext

    override suspend fun getAuthorizedSeeds(): List<AlgorandSeedVaultSeed> =
        withContext(coroutineDispatcher) {
            // Each call owns its connection so concurrent reads cannot unbind one another.
            val client = SeedVaultServiceClient(applicationContext)
            try {
                checkNotNull(withTimeoutOrNull(BIND_TIMEOUT_MILLIS) { client.bindAsync() }) {
                    "Timed out connecting to Seed Vault Service"
                }
                client.getAuthorizedSeeds().map { it.toAlgorandSeedVaultSeed() }
            } finally {
                client.unbind()
            }
        }

    private companion object {
        const val BIND_TIMEOUT_MILLIS = 10_000L
    }
}

internal fun SeedVaultSeed.toAlgorandSeedVaultSeed(): AlgorandSeedVaultSeed =
    AlgorandSeedVaultSeed(
        id = id.toString(),
        name = name,
        accounts =
            accounts.map { account ->
                SeedVaultAccount(
                    publicKey = account.publicKey,
                    address = account.address,
                    chainId = "283",
                    accountName = name,
                )
            },
    )
