package com.michaeltchuang.walletsdk.seedvault.service.data.repository

import com.michaeltchuang.walletsdk.core.algosdk.getFalcon25MnemonicFromEntropy
import com.michaeltchuang.walletsdk.core.algosdk.recoverFalcon25Account
import com.michaeltchuang.walletsdk.core.algosdk.signFalcon25ArbitraryData
import com.michaeltchuang.walletsdk.core.encryption.decryptByteArray
import com.michaeltchuang.walletsdk.core.encryption.encryptByteArray
import com.michaeltchuang.walletsdk.core.encryption.initializeEncryptionManager
import com.michaeltchuang.walletsdk.seedvault.service.data.database.SeedDao
import com.michaeltchuang.walletsdk.seedvault.service.data.database.SeedEntity
import com.michaeltchuang.walletsdk.seedvault.service.data.database.SeedWithApps
import com.michaeltchuang.walletsdk.seedvault.service.domain.model.AuthorizedApp
import com.michaeltchuang.walletsdk.seedvault.service.domain.model.VaultSeed
import com.michaeltchuang.walletsdk.seedvault.service.domain.repository.SeedVaultRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Room backed [SeedVaultRepository]. The Falcon-1024 entropy behind each 25-word seed phrase and
 * the private key derived from it are stored encrypted with the AES-GCM Android Keystore key from
 * wallet-sdk-core, next to the public key and address.
 */
internal class SeedVaultRepositoryImpl(
    private val seedDao: SeedDao,
    private val coroutineDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SeedVaultRepository {
    private val keyMutex = Mutex()
    private var isKeyReady = false

    override fun getSeeds(): Flow<List<VaultSeed>> = seedDao.getAllAsFlow().map { seeds -> seeds.map { it.toVaultSeed() } }

    override fun getSeed(seedId: Long): Flow<VaultSeed?> = getSeeds().map { seeds -> seeds.firstOrNull { it.id == seedId } }

    override suspend fun addSeed(
        seedPhrase: List<String>,
        isBackedUp: Boolean,
    ): VaultSeed =
        withContext(coroutineDispatcher) {
            ensureEncryptionKey()
            val account = checkNotNull(recoverFalcon25Account(seedPhrase.joinToString(" "))) { "Could not derive the Falcon-1024 account" }
            val entity =
                try {
                    SeedEntity(
                        name = "",
                        isBackedUp = isBackedUp,
                        encryptedEntropy = encryptByteArray(account.entropy),
                        address = account.address,
                        publicKey = account.publicKey,
                        encryptedPrivateKey = encryptByteArray(account.privateKey),
                    )
                } finally {
                    account.entropy.fill(0)
                    account.seed.fill(0)
                    account.privateKey.fill(0)
                }
            val id = seedDao.insertWithDefaultName(entity)
            checkNotNull(seedDao.get(id)) { "Seed $id was not saved" }.toVaultSeed()
        }

    override suspend fun signBytes(
        seedId: Long,
        data: ByteArray,
    ): ByteArray? =
        withContext(coroutineDispatcher) {
            val publicKey = seedDao.getPublicKey(seedId) ?: return@withContext null
            val encryptedPrivateKey = seedDao.getEncryptedPrivateKey(seedId) ?: return@withContext null
            ensureEncryptionKey()
            val privateKey = decryptByteArray(encryptedPrivateKey)
            try {
                signFalcon25ArbitraryData(data, publicKey, privateKey)
            } finally {
                privateKey.fill(0)
            }
        }

    override suspend fun getSeedPhrase(seedId: Long): List<String>? =
        withContext(coroutineDispatcher) {
            val encryptedEntropy = seedDao.getEncryptedEntropy(seedId) ?: return@withContext null
            ensureEncryptionKey()
            val entropy = decryptByteArray(encryptedEntropy)
            try {
                checkNotNull(getFalcon25MnemonicFromEntropy(entropy)) { "Could not encode the Falcon-1024 seed phrase" }
                    .split(" ")
            } finally {
                entropy.fill(0)
            }
        }

    override suspend fun renameSeed(
        seedId: Long,
        name: String,
    ) {
        seedDao.rename(seedId, name)
    }

    override suspend fun deleteSeed(seedId: Long) {
        seedDao.delete(seedId)
    }

    override suspend fun revokeApp(
        seedId: Long,
        packageName: String,
    ) {
        seedDao.revokeApp(seedId, packageName)
    }

    /**
     * Creates the Keystore key before the first encrypt / decrypt. wallet-sdk-core also does this
     * from a background coroutine when its AES manager is created, which could race the first write.
     */
    private suspend fun ensureEncryptionKey() {
        keyMutex.withLock {
            if (!isKeyReady) {
                initializeEncryptionManager()
                isKeyReady = true
            }
        }
    }

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
}
