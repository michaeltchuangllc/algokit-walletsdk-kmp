package com.michaeltchuang.walletsdk.core.account.data.repository

import com.michaeltchuang.walletsdk.core.account.data.database.dao.HdKeyDao
import com.michaeltchuang.walletsdk.core.account.data.database.dao.HdSeedDao
import com.michaeltchuang.walletsdk.core.account.data.mapper.entity.HdKeyEntityMapper
import com.michaeltchuang.walletsdk.core.account.data.mapper.model.HdKeyMapper
import com.michaeltchuang.walletsdk.core.account.data.mapper.model.HdSeedWalletSummaryMapper
import com.michaeltchuang.walletsdk.core.account.data.mapper.model.HdWalletSummaryMapper
import com.michaeltchuang.walletsdk.core.account.domain.model.local.HdWalletSummary
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount.HdKey
import com.michaeltchuang.walletsdk.core.account.domain.repository.local.HdKeyAccountRepository
import com.michaeltchuang.walletsdk.core.encryption.decryptByteArray
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

internal class HdKeyAccountRepositoryImpl(
    private val hdKeyDao: HdKeyDao,
    private val hdKeyEntityMapper: HdKeyEntityMapper,
    private val hdWalletSummaryMapper: HdWalletSummaryMapper,
    private val hdKeyMapper: HdKeyMapper,
    private val hdSeedDao: HdSeedDao,
    private val hdSeedWalletSummaryMapper: HdSeedWalletSummaryMapper,
    private val coroutineDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : HdKeyAccountRepository {
    override fun getAllAsFlow(): Flow<List<HdKey>> =
        hdKeyDao.getAllAsFlow().map { entityList ->
            entityList.map { entity -> hdKeyMapper(entity) }
        }

    override fun getAccountCountAsFlow(): Flow<Int> = hdKeyDao.getTableSizeAsFlow()

    override suspend fun getAccountCount(): Int = hdKeyDao.getTableSize()

    override suspend fun getAll(): List<HdKey> =
        withContext(coroutineDispatcher) {
            val hdKeyEntities = hdKeyDao.getAll()
            hdKeyEntities.map { hdKeyMapper(it) }
        }

    override suspend fun getAllAddresses(): List<String> =
        withContext(coroutineDispatcher) {
            hdKeyDao.getAllAddresses()
        }

    override suspend fun getAccount(address: String): HdKey? =
        withContext(coroutineDispatcher) {
            hdKeyDao.get(address)?.let { hdKeyMapper(it) }
        }

    override suspend fun getDerivedAddressCountOfSeed(seedId: Int): Int =
        withContext(coroutineDispatcher) {
            hdKeyDao.getDerivedAddressCountOfSeed(seedId)
        }

    override suspend fun addAccount(
        account: HdKey,
        privateKey: ByteArray,
    ) {
        withContext(coroutineDispatcher) {
            val hdKeyEntity = hdKeyEntityMapper(account, privateKey)
            hdKeyDao.insert(hdKeyEntity)
        }
    }

    override suspend fun deleteAccount(address: String) {
        withContext(coroutineDispatcher) {
            hdKeyDao.delete(address)
        }
    }

    override suspend fun deleteAllAccounts() {
        withContext(coroutineDispatcher) {
            hdKeyDao.clearAll()
        }
    }

    override suspend fun getPrivateKey(address: String): ByteArray? =
        withContext(coroutineDispatcher) {
            val encryptedSK = hdKeyDao.get(address)?.encryptedPrivateKey
             encryptedSK?.let {decryptByteArray(it) }
        }

    override suspend fun getHdWalletSummaries(): List<HdWalletSummary> =
        withContext(coroutineDispatcher) {
            val walletEntities = hdSeedDao.getAll()
            val hdKeyEntities = hdKeyDao.getAll()
            val hdKeyGroups = hdKeyEntities.groupBy { it.seedId }
            val hdWalletSummaries =
                walletEntities.map { hdSeedEntity ->
                    val seedId = hdSeedEntity.seedId
                    val keyGroup = hdKeyGroups[seedId]
                    val accountCount = keyGroup?.size ?: 0
                    val representativeKey = keyGroup?.maxByOrNull { it.account }
                    if (representativeKey != null) {
                        hdWalletSummaryMapper(representativeKey, accountCount)
                    } else {
                        hdSeedWalletSummaryMapper(hdSeedEntity, accountCount)
                    }
                }

            hdWalletSummaries
        }

    override suspend fun getHdSeedId(address: String): Int? =
        withContext(coroutineDispatcher) {
            hdKeyDao.getHdSeedId(address)
        }
}
