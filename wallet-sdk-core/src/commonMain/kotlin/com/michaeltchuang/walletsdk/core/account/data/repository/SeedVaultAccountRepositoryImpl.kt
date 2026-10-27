package com.michaeltchuang.walletsdk.core.account.data.repository

import com.michaeltchuang.walletsdk.core.account.data.database.dao.SeedVaultDao
import com.michaeltchuang.walletsdk.core.account.data.mapper.entity.SeedVaultAccountEntityMapper
import com.michaeltchuang.walletsdk.core.account.data.mapper.model.SeedVaultAccountMapper
import com.michaeltchuang.walletsdk.core.account.domain.model.local.SeedVaultAccount
import com.michaeltchuang.walletsdk.core.account.domain.repository.local.SeedVaultAccountRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

internal class SeedVaultAccountRepositoryImpl(
    private val seedVaultAccountDao: SeedVaultDao,
    private val seedVaultAccountEntityMapper: SeedVaultAccountEntityMapper,
    private val seedVaultAccountMapper: SeedVaultAccountMapper,
    private val coroutineDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SeedVaultAccountRepository {
    override fun getAllAsFlow(): Flow<List<SeedVaultAccount>> =
        seedVaultAccountDao.getAllAsFlow().map { entityList ->
            entityList.map { entity -> seedVaultAccountMapper(entity) }
        }

    override fun getAccountCountAsFlow(): Flow<Int> = seedVaultAccountDao.getTableSizeAsFlow()

    override suspend fun getAccountCount(): Int = seedVaultAccountDao.getTableSize()

    override suspend fun getAll(): List<SeedVaultAccount> =
        withContext(coroutineDispatcher) {
            val entities = seedVaultAccountDao.getAll()
            entities.map { seedVaultAccountMapper(it) }
        }

    override suspend fun getAllAddresses(): List<String> =
        withContext(coroutineDispatcher) {
            seedVaultAccountDao.getAllAddresses()
        }

    override suspend fun getAccount(publicKey: String): SeedVaultAccount? =
        withContext(coroutineDispatcher) {
            val entity = seedVaultAccountDao.get(publicKey)
            entity?.let { seedVaultAccountMapper(it) }
        }

    override suspend fun addAccount(account: SeedVaultAccount) {
        withContext(coroutineDispatcher) {
            val entity = seedVaultAccountEntityMapper(account)
            seedVaultAccountDao.insert(entity)
        }
    }

    override suspend fun addAccounts(accounts: List<SeedVaultAccount>) {
        withContext(coroutineDispatcher) {
            val entities = accounts.map { seedVaultAccountEntityMapper(it) }
            seedVaultAccountDao.insertAll(entities)
        }
    }

    override suspend fun deleteAccount(publicKey: String) {
        withContext(coroutineDispatcher) {
            seedVaultAccountDao.delete(publicKey)
        }
    }

    override suspend fun deleteAccountByAddress(address: String) {
        withContext(coroutineDispatcher) {
            seedVaultAccountDao.deleteByAddress(address)
        }
    }

    override suspend fun isAddressExists(address: String): Boolean =
        withContext(coroutineDispatcher) {
            seedVaultAccountDao.isAddressExists(address)
        }

    override suspend fun updateAccountNameByAddress(
        address: String,
        accountName: String?,
    ) {
        withContext(coroutineDispatcher) {
            seedVaultAccountDao.updateAccountNameByAddress(address, accountName)
        }
    }

    override suspend fun deleteAllAccounts() {
        withContext(coroutineDispatcher) {
            seedVaultAccountDao.clearAll()
        }
    }
}
