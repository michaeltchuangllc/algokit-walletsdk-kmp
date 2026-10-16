package com.michaeltchuang.walletsdk.seedvault.service.domain.usecase

import com.michaeltchuang.walletsdk.seedvault.service.domain.model.VaultSeed
import kotlinx.coroutines.flow.Flow

fun interface GetSeeds {
    operator fun invoke(): Flow<List<VaultSeed>>
}

fun interface GetSeed {
    operator fun invoke(seedId: Long): Flow<VaultSeed?>
}

/** Creates a new 25-word Falcon-1024 seed phrase. Nothing is stored until [AddSeed]. */
fun interface GenerateSeedPhrase {
    suspend operator fun invoke(): List<String>
}

fun interface AddSeed {
    suspend operator fun invoke(
        seedPhrase: List<String>,
        isBackedUp: Boolean,
    ): VaultSeed
}

fun interface GetSeedPhrase {
    suspend operator fun invoke(seedId: Long): List<String>?
}

fun interface RenameSeed {
    suspend operator fun invoke(
        seedId: Long,
        name: String,
    )
}

fun interface DeleteSeed {
    suspend operator fun invoke(seedId: Long)
}

fun interface RevokeAuthorizedApp {
    suspend operator fun invoke(
        seedId: Long,
        packageName: String,
    )
}
