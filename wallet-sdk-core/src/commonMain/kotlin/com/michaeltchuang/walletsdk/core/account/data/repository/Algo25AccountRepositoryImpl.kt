package com.michaeltchuang.walletsdk.core.account.data.repository

import com.michaeltchuang.walletsdk.core.account.data.database.dao.Algo25Dao
import com.michaeltchuang.walletsdk.core.account.data.mapper.entity.Algo25EntityMapper
import com.michaeltchuang.walletsdk.core.account.data.mapper.model.Algo25Mapper
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount.Algo25
import com.michaeltchuang.walletsdk.core.account.domain.repository.local.Algo25AccountRepository
import com.michaeltchuang.walletsdk.core.encryption.decryptByteArray
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

internal class Algo25AccountRepositoryImpl(
    private val algo25Dao: Algo25Dao,
    private val algo25EntityMapper: Algo25EntityMapper,
    private val algo25Mapper: Algo25Mapper,
    private val coroutineDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Algo25AccountRepository {
    override fun getAllAsFlow(): Flow<List<Algo25>> =
        algo25Dao.getAllAsFlow().map { entityList ->
            entityList.map { entity -> algo25Mapper(entity) }
        }

    override fun getAccountCountAsFlow(): Flow<Int> = algo25Dao.getTableSizeAsFlow()

    override suspend fun getAccountCount(): Int = algo25Dao.getTableSize()

    override suspend fun getAll(): List<Algo25> =
        withContext(coroutineDispatcher) {
            val algo25Entities = algo25Dao.getAll()
            algo25Entities.map { algo25Mapper(it) }
        }

    override suspend fun getAllAddresses(): List<String> =
        withContext(coroutineDispatcher) {
            algo25Dao.getAllAddresses()
        }

    override suspend fun getAccount(address: String): Algo25? =
        withContext(coroutineDispatcher) {
            algo25Dao.get(address)?.let { algo25Mapper(it) }
        }

    override suspend fun addAccount(
        account: Algo25,
        privateKey: ByteArray,
    ) {
        withContext(coroutineDispatcher) {
            val algo25Entity = algo25EntityMapper(account, privateKey)
            algo25Dao.insert(algo25Entity)
        }
    }

    override suspend fun deleteAccount(address: String) {
        withContext(coroutineDispatcher) {
            algo25Dao.delete(address)
        }
    }

    override suspend fun deleteAllAccounts() {
        withContext(coroutineDispatcher) {
            algo25Dao.clearAll()
        }
    }

    override suspend fun getSecretKey(address: String): ByteArray? =
        withContext(coroutineDispatcher) {
            val encryptedSK = algo25Dao.get(address)?.encryptedSecretKey
             encryptedSK?.let {decryptByteArray(it) }
        }
}
