package com.michaeltchuang.walletsdk.core.account.data.repository

import com.michaeltchuang.walletsdk.core.account.data.database.dao.HdSeedDao
import com.michaeltchuang.walletsdk.core.account.data.mapper.entity.HdSeedEntityMapper
import com.michaeltchuang.walletsdk.core.account.data.mapper.model.HdSeedMapper
import com.michaeltchuang.walletsdk.core.account.domain.model.local.HdSeed
import com.michaeltchuang.walletsdk.core.account.domain.repository.local.HdSeedRepository
import com.michaeltchuang.walletsdk.core.encryption.decryptByteArray
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

internal class HdSeedRepositoryImpl(
    private val hdSeedDao: HdSeedDao,
    private val hdSeedEntityMapper: HdSeedEntityMapper,
    private val hdSeedMapper: HdSeedMapper,
    private val coroutineDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : HdSeedRepository {
    override fun getAllAsFlow(): Flow<List<HdSeed>> =
        hdSeedDao.getAllAsFlow().map { entityList ->
            entityList.map { entity -> hdSeedMapper(entity) }
        }

    override fun getSeedCountAsFlow(): Flow<Int> = hdSeedDao.getTableSizeAsFlow()

    override suspend fun getHdSeedCount(): Int = hdSeedDao.getTableSize()

    override suspend fun getMaxSeedId(): Int? = hdSeedDao.getMaxSeedId()

    override suspend fun hasAnySeed(): Boolean = hdSeedDao.hasAnySeed()

    override suspend fun getSeedIdIfExistingEntropy(entropy: ByteArray): Int? {
        val entities = hdSeedDao.getAll()

        for (entity in entities) {
            val decryptedEntropy = decryptByteArray(entity.encryptedEntropy)
            if (entropy.contentEquals(decryptedEntropy)) {
                return entity.seedId
            }
        }
        return null
    }

    override suspend fun getAllHdSeeds(): List<HdSeed> =
        withContext(coroutineDispatcher) {
            val entities = hdSeedDao.getAll()
            entities.map { hdSeedMapper(it) }
        }

    override suspend fun getHdSeed(seedId: Int): HdSeed? =
        withContext(coroutineDispatcher) {
            hdSeedDao.get(seedId)?.let { hdSeedMapper(it) }
        }

    override suspend fun addHdSeed(
        seedId: Int,
        entropy: ByteArray,
        seed: ByteArray,
    ): Long =
        withContext(coroutineDispatcher) {
            val hdKeyEntity = hdSeedEntityMapper(seedId, entropy, seed)
            val seedId = hdSeedDao.insert(hdKeyEntity)
            seedId
        }

    override suspend fun deleteHdSeed(seedId: Int) {
        withContext(coroutineDispatcher) {
            hdSeedDao.delete(seedId)
            if (hdSeedDao.getTableSize() < 1) {
                hdSeedDao.clearPrimaryKeyIndex()
            }
        }
    }

    override suspend fun deleteAllHdSeeds() {
        withContext(coroutineDispatcher) {
            hdSeedDao.clearAll()
            hdSeedDao.clearPrimaryKeyIndex()
        }
    }

    override suspend fun getEntropy(seedId: Int): ByteArray? =
        withContext(coroutineDispatcher) {
            val encryptedSK = hdSeedDao.get(seedId)?.encryptedEntropy
            encryptedSK?.let { decryptByteArray(it) }
        }

    override suspend fun getSeed(seedId: Int): ByteArray? =
        withContext(coroutineDispatcher) {
            val encryptedSK = hdSeedDao.get(seedId)?.encryptedSeed
            encryptedSK?.let { decryptByteArray(it) }
        }
}
