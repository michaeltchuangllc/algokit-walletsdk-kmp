package com.michaeltchuang.walletsdk.core.account.data.repository

import com.michaeltchuang.walletsdk.core.account.data.database.dao.Falcon24Dao
import com.michaeltchuang.walletsdk.core.account.data.database.dao.HdSeedDao
import com.michaeltchuang.walletsdk.core.account.data.mapper.entity.Falcon24EntityMapper
import com.michaeltchuang.walletsdk.core.account.data.mapper.model.Falcon24Mapper
import com.michaeltchuang.walletsdk.core.account.data.mapper.model.HdSeedWalletSummaryMapper
import com.michaeltchuang.walletsdk.core.account.domain.model.local.HdWalletSummary
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount.Falcon24
import com.michaeltchuang.walletsdk.core.account.domain.repository.local.Falcon24AccountRepository
import com.michaeltchuang.walletsdk.core.encryption.decryptByteArray
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.collections.map

internal class Falcon24AccountRepositoryImpl(
    private val hdSeedDao: HdSeedDao,
    private val falcon24Dao: Falcon24Dao,
    private val falcon24EntityMapper: Falcon24EntityMapper,
    private val falcon24Mapper: Falcon24Mapper,
    private val hdSeedWalletSummaryMapper: HdSeedWalletSummaryMapper,
    private val coroutineDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Falcon24AccountRepository {
    override fun getAllAsFlow(): Flow<List<Falcon24>> =
        falcon24Dao.getAllAsFlow().map { entityList ->
            entityList.map { entity -> falcon24Mapper(entity) }
        }

    override fun getAccountCountAsFlow(): Flow<Int> = falcon24Dao.getTableSizeAsFlow()

    override suspend fun getAccountCount(): Int = falcon24Dao.getTableSize()

    override suspend fun getAll(): List<Falcon24> =
        withContext(coroutineDispatcher) {
            val Falcon24Entities = falcon24Dao.getAll()
            Falcon24Entities.map { falcon24Mapper(it) }
        }

    override suspend fun getAllAddresses(): List<String> =
        withContext(coroutineDispatcher) {
            falcon24Dao.getAllAddresses()
        }

    override suspend fun getAccount(address: String): Falcon24? =
        withContext(coroutineDispatcher) {
            falcon24Dao.get(address)?.let { falcon24Mapper(it) }
        }

    override suspend fun addAccount(
        account: Falcon24,
        seedId: Int,
        privateKey: ByteArray,
    ) {
        withContext(coroutineDispatcher) {
            val Falcon24Entity = falcon24EntityMapper(account, seedId, privateKey)
            falcon24Dao.insert(Falcon24Entity)
        }
    }

    override suspend fun deleteAccount(address: String) {
        withContext(coroutineDispatcher) {
            falcon24Dao.delete(address)
        }
    }

    override suspend fun deleteAllAccounts() {
        withContext(coroutineDispatcher) {
            falcon24Dao.clearAll()
        }
    }

    override suspend fun getDerivedAddressCountOfSeed(seedId: Int): Int =
        withContext(coroutineDispatcher) {
            falcon24Dao.getDerivedAddressCountOfSeed(seedId)
        }

    override suspend fun getSecretKey(address: String): ByteArray? =
        withContext(coroutineDispatcher) {
            val encryptedSK = falcon24Dao.get(address)?.encryptedSecretKey
             encryptedSK?.let { decryptByteArray(it) }
        }

    override suspend fun getHdWalletSummaries(): List<HdWalletSummary> =
        withContext(coroutineDispatcher) {
            val walletEntities = hdSeedDao.getAll()
            val falcon24Entities = falcon24Dao.getAll()
            val seedsWithAccounts = falcon24Entities.map { it.seedId }.toSet()
            val emptyHdWalletEntities = walletEntities.filter { it.seedId !in seedsWithAccounts }

            val emptyHdWalletSummaries =
                emptyHdWalletEntities.map { hdSeed ->
                    hdSeedWalletSummaryMapper(hdSeed, 0)
                }

            emptyHdWalletSummaries
        }
}
