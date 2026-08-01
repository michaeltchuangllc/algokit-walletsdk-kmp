package com.michaeltchuang.walletsdk.core.account.data.repository

import com.michaeltchuang.walletsdk.core.account.data.database.dao.Falcon25Dao
import com.michaeltchuang.walletsdk.core.account.data.mapper.entity.Falcon25EntityMapper
import com.michaeltchuang.walletsdk.core.account.data.mapper.model.Falcon25Mapper
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount.Falcon25
import com.michaeltchuang.walletsdk.core.account.domain.repository.local.Falcon25AccountRepository
import com.michaeltchuang.walletsdk.core.encryption.decryptByteArray
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

internal class Falcon25AccountRepositoryImpl(
    private val falcon25Dao: Falcon25Dao,
    private val falcon25EntityMapper: Falcon25EntityMapper,
    private val falcon25Mapper: Falcon25Mapper,
    private val coroutineDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Falcon25AccountRepository {
    override fun getAllAsFlow(): Flow<List<Falcon25>> =
        falcon25Dao.getAllAsFlow().map { entityList ->
            entityList.map { entity -> falcon25Mapper(entity) }
        }

    override fun getAccountCountAsFlow(): Flow<Int> = falcon25Dao.getTableSizeAsFlow()

    override suspend fun getAccountCount(): Int = falcon25Dao.getTableSize()

    override suspend fun getAll(): List<Falcon25> =
        withContext(coroutineDispatcher) {
            val falcon25Entities = falcon25Dao.getAll()
            falcon25Entities.map { falcon25Mapper(it) }
        }

    override suspend fun getAllAddresses(): List<String> =
        withContext(coroutineDispatcher) {
            falcon25Dao.getAllAddresses()
        }

    override suspend fun getAccount(address: String): Falcon25? =
        withContext(coroutineDispatcher) {
            falcon25Dao.get(address)?.let { falcon25Mapper(it) }
        }

    override suspend fun addAccount(
        account: Falcon25,
        privateKey: ByteArray,
        entropy: ByteArray,
    ) {
        withContext(coroutineDispatcher) {
            val falcon25Entity = falcon25EntityMapper(account, privateKey, entropy)
            falcon25Dao.insert(falcon25Entity)
        }
    }

    override suspend fun deleteAccount(address: String) {
        withContext(coroutineDispatcher) {
            falcon25Dao.delete(address)
        }
    }

    override suspend fun deleteAllAccounts() {
        withContext(coroutineDispatcher) {
            falcon25Dao.clearAll()
        }
    }

    override suspend fun getPrivateKey(address: String): ByteArray? =
        withContext(coroutineDispatcher) {
            val encryptedPrivateKey = falcon25Dao.get(address)?.encryptedPrivateKey
            encryptedPrivateKey?.let { decryptByteArray(it) }
        }

    override suspend fun getEntropy(address: String): ByteArray? =
        withContext(coroutineDispatcher) {
            val encryptedEntropy = falcon25Dao.get(address)?.encryptedEntropy
            encryptedEntropy?.let { decryptByteArray(it) }
        }
}
