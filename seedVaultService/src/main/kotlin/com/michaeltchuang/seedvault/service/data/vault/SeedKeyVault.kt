package com.michaeltchuang.seedvault.service.data.vault

import com.michaeltchuang.seedvault.service.domain.model.AlgorandPaymentParams

/** Where the secret keys of a seed live. The name is stored in the seed row. */
internal enum class SeedStorage {
    /** Encrypted with the Android Keystore key and kept in the Room database. */
    ROOM,

    /** Inside the Trusty applet in the TEE, which never hands out the keys. */
    TRUSTY,
}

/**
 * What the seed row holds for a seed. The public values are always set. The encrypted values are
 * only used by [SeedStorage.ROOM]; for [SeedStorage.TRUSTY] they are empty, since the secrets stay
 * in the TEE.
 */
internal class StoredSeed(
    val address: String,
    val publicKey: ByteArray,
    val encryptedEntropy: ByteArray,
    val encryptedPrivateKey: ByteArray,
)

/**
 * Keeps the secrets of Falcon-1024 seeds and does everything that needs them. The repository picks
 * an implementation per seed, so the rest of the service does not care where the keys are.
 */
internal interface SeedKeyVault {
    val storage: SeedStorage

    /** False when this vault cannot be used on the device, e.g. there is no Trusty applet. */
    suspend fun isAvailable(): Boolean

    /** Stores a 25-word seed phrase and returns what the seed row needs to keep. */
    suspend fun addSeed(seedPhrase: List<String>): StoredSeed

    suspend fun revealSeedPhrase(seed: StoredSeed): List<String>

    /** Signs raw bytes. Throws [IllegalArgumentException] if the vault refuses the data. */
    suspend fun signBytes(
        seed: StoredSeed,
        data: ByteArray,
    ): ByteArray?

    /** Signs an Algorand payment of [amountMicroAlgos] from the seed's account to [receiver]. */
    suspend fun signAlgorandPayment(
        seed: StoredSeed,
        receiver: String,
        amountMicroAlgos: Long,
        params: AlgorandPaymentParams,
    ): ByteArray?

    /** Removes the secrets of the seed. */
    suspend fun deleteSeed(seed: StoredSeed)
}
