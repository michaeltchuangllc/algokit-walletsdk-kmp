package com.michaeltchuang.seedvault.service.data.repository

import android.util.Log
import com.michaeltchuang.seedvault.service.data.database.AuthorizedAppEntity
import com.michaeltchuang.seedvault.service.data.database.SeedDao
import com.michaeltchuang.seedvault.service.data.database.SeedEntity
import com.michaeltchuang.seedvault.service.data.database.SeedWithApps
import com.michaeltchuang.seedvault.service.data.vault.SeedKeyVault
import com.michaeltchuang.seedvault.service.data.vault.SeedStorage
import com.michaeltchuang.seedvault.service.data.vault.StoredSeed
import com.michaeltchuang.seedvault.service.domain.model.AlgorandPaymentParams
import com.michaeltchuang.seedvault.service.domain.model.AuthorizedApp
import com.michaeltchuang.seedvault.service.domain.model.VaultSeed
import com.michaeltchuang.seedvault.service.domain.repository.SeedVaultRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Room backed [SeedVaultRepository]. Names, backup state, addresses, public keys and authorized
 * apps live in Room. The secret keys live in a [SeedKeyVault]: the Trusty applet in the TEE when the
 * device has one, otherwise encrypted in the database with the Android Keystore. Each seed
 * remembers which one holds its keys.
 */
internal class SeedVaultRepositoryImpl(
    private val seedDao: SeedDao,
    private val roomVault: SeedKeyVault,
    private val trustyVault: SeedKeyVault,
    private val coroutineDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SeedVaultRepository {
    override fun getSeeds(): Flow<List<VaultSeed>> = seedDao.getAllAsFlow().map { seeds -> seeds.map { it.toVaultSeed() } }

    override fun getSeed(seedId: Long): Flow<VaultSeed?> = getSeeds().map { seeds -> seeds.firstOrNull { it.id == seedId } }

    override suspend fun addSeed(
        seedPhrase: List<String>,
        isBackedUp: Boolean,
    ): VaultSeed =
        withContext(coroutineDispatcher) {
            val vault = if (trustyVault.isAvailable()) trustyVault else roomVault
            Log.i(TAG, "Storing the keys of a new seed in ${vault.storage}")
            val stored = vault.addSeed(seedPhrase)
            val id =
                seedDao.insertWithDefaultName(
                    SeedEntity(
                        name = "",
                        isBackedUp = isBackedUp,
                        encryptedEntropy = stored.encryptedEntropy,
                        address = stored.address,
                        publicKey = stored.publicKey,
                        encryptedPrivateKey = stored.encryptedPrivateKey,
                        storage = vault.storage.name,
                    ),
                )
            val saved = checkNotNull(seedDao.get(id)) { "Seed $id was not saved" }
            if (saved.seed.storage != vault.storage.name) {
                // The phrase was already stored somewhere else, so the keys just added are not needed
                vault.deleteSeed(stored)
            }
            saved.toVaultSeed()
        }

    override suspend fun signBytes(
        seedId: Long,
        data: ByteArray,
    ): ByteArray? =
        withContext(coroutineDispatcher) {
            val seed = seedDao.getSeed(seedId) ?: return@withContext null
            vaultFor(seed).signBytes(seed.toStoredSeed(), data)
        }

    override suspend fun signAlgorandPayment(
        seedId: Long,
        receiver: String,
        amountMicroAlgos: Long,
        params: AlgorandPaymentParams,
    ): ByteArray? =
        withContext(coroutineDispatcher) {
            val seed = seedDao.getSeed(seedId) ?: return@withContext null
            vaultFor(seed).signAlgorandPayment(seed.toStoredSeed(), receiver, amountMicroAlgos, params)
        }

    override suspend fun getSeedPhrase(seedId: Long): List<String>? =
        withContext(coroutineDispatcher) {
            val seed = seedDao.getSeed(seedId) ?: return@withContext null
            vaultFor(seed).revealSeedPhrase(seed.toStoredSeed())
        }

    override suspend fun getPublicKey(seedId: Long): ByteArray? = seedDao.getPublicKey(seedId)

    override suspend fun authorizeApp(
        seedId: Long,
        packageName: String,
        label: String,
    ) {
        seedDao.insertAuthorizedApp(
            AuthorizedAppEntity(seedId = seedId, packageName = packageName, label = label, isPrivileged = false),
        )
    }

    override suspend fun renameSeed(
        seedId: Long,
        name: String,
    ) {
        seedDao.rename(seedId, name)
    }

    override suspend fun deleteSeed(seedId: Long) {
        withContext(coroutineDispatcher) {
            // The keys go first: if they cannot be removed the seed stays, so it can be tried again
            seedDao.getSeed(seedId)?.let { vaultFor(it).deleteSeed(it.toStoredSeed()) }
            seedDao.delete(seedId)
        }
    }

    override suspend fun revokeApp(
        seedId: Long,
        packageName: String,
    ) {
        seedDao.revokeApp(seedId, packageName)
    }

    /** The vault that holds the keys of [seed]. */
    private suspend fun vaultFor(seed: SeedEntity): SeedKeyVault {
        val vault =
            when (SeedStorage.valueOf(seed.storage)) {
                SeedStorage.ROOM -> roomVault
                SeedStorage.TRUSTY -> trustyVault
            }
        check(vault.isAvailable()) { "The keys of this seed are in ${seed.storage}, which is not available on this device" }
        return vault
    }

    private fun SeedEntity.toStoredSeed() =
        StoredSeed(
            address = address,
            publicKey = publicKey,
            encryptedEntropy = encryptedEntropy,
            encryptedPrivateKey = encryptedPrivateKey,
        )

    private fun SeedWithApps.toVaultSeed() =
        VaultSeed(
            id = seed.id,
            name = seed.name,
            isBackedUp = seed.isBackedUp,
            authorizedApps =
                apps.map {
                    AuthorizedApp(packageName = it.packageName, label = it.label, isPrivileged = it.isPrivileged)
                },
            address = seed.address,
        )

    private companion object {
        const val TAG = "SeedVaultRepository"
    }
}
