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

/** Checks that [words] are a complete, correctly checksummed 25-word Falcon-1024 seed phrase. */
fun interface ValidateSeedPhrase {
    suspend operator fun invoke(words: List<String>): Boolean
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

/** Signs a byte array with the Falcon-1024 key of a seed. */
fun interface SignBytes {
    suspend operator fun invoke(
        seedId: Long,
        data: ByteArray,
    ): ByteArray?
}

/** Lets an app request signatures from a seed. */
fun interface AuthorizeApp {
    suspend operator fun invoke(
        seedId: Long,
        packageName: String,
        label: String,
    )
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
