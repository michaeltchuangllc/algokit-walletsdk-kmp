package com.michaeltchuang.walletsdk.core.account.data.repository

import com.michaeltchuang.walletsdk.core.account.data.database.dao.SeedVaultDao
import com.michaeltchuang.walletsdk.core.account.data.mapper.entity.SolanaAccountEntityMapper
import com.michaeltchuang.walletsdk.core.account.data.mapper.model.SolanaAccountMapper
import com.michaeltchuang.walletsdk.core.account.domain.model.local.SolanaAccount
import com.michaeltchuang.walletsdk.core.account.domain.repository.local.SolanaAccountRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

internal class SolanaAccountRepositoryImpl(
    private val solanaAccountDao: SeedVaultDao,
    private val solanaAccountEntityMapper: SolanaAccountEntityMapper,
    private val solanaAccountMapper: SolanaAccountMapper,
    private val coroutineDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SolanaAccountRepository {
    override fun getAllAsFlow(): Flow<List<SolanaAccount>> =
        solanaAccountDao.getAllAsFlow().map { entityList ->
            entityList.map { entity -> solanaAccountMapper(entity) }
        }

    override fun getAccountCountAsFlow(): Flow<Int> = solanaAccountDao.getTableSizeAsFlow()

    override suspend fun getAccountCount(): Int = solanaAccountDao.getTableSize()

    override suspend fun getAll(): List<SolanaAccount> =
        withContext(coroutineDispatcher) {
            val entities = solanaAccountDao.getAll()
            entities.map { solanaAccountMapper(it) }
        }

    override suspend fun getAllAddresses(): List<String> =
        withContext(coroutineDispatcher) {
            solanaAccountDao.getAllAddresses()
        }

    override suspend fun getAccount(publicKey: String): SolanaAccount? =
        withContext(coroutineDispatcher) {
            val entity = solanaAccountDao.get(publicKey)
            entity?.let { solanaAccountMapper(it) }
        }

    override suspend fun addAccount(account: SolanaAccount) {
        withContext(coroutineDispatcher) {
            val entity = solanaAccountEntityMapper(account)
            solanaAccountDao.insert(entity)
        }
    }

    override suspend fun addAccounts(accounts: List<SolanaAccount>) {
        withContext(coroutineDispatcher) {
            val entities = accounts.map { solanaAccountEntityMapper(it) }
            solanaAccountDao.insertAll(entities)
        }
    }

    override suspend fun deleteAccount(publicKey: String) {
        withContext(coroutineDispatcher) {
            solanaAccountDao.delete(publicKey)
        }
    }
    
    override suspend fun deleteAccountByAddress(address: String) {
        withContext(coroutineDispatcher) {
            solanaAccountDao.deleteByAddress(address)
        }
    }

    override suspend fun isAddressExists(address: String): Boolean =
        withContext(coroutineDispatcher) {
            solanaAccountDao.isAddressExists(address)
        }
    
    override suspend fun updateAccountNameByAddress(address: String, accountName: String?) {
        withContext(coroutineDispatcher) {
            solanaAccountDao.updateAccountNameByAddress(address, accountName)
        }
    }

    override suspend fun deleteAllAccounts() {
        withContext(coroutineDispatcher) {
            solanaAccountDao.clearAll()
        }
    }
}
