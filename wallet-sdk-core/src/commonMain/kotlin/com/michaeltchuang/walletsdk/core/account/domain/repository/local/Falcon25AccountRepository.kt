package com.michaeltchuang.walletsdk.core.account.domain.repository.local

import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import kotlinx.coroutines.flow.Flow

interface Falcon25AccountRepository {
    fun getAllAsFlow(): Flow<List<LocalAccount.Falcon25>>
    fun getAccountCountAsFlow(): Flow<Int>
    suspend fun getAccountCount(): Int
    suspend fun getAll(): List<LocalAccount.Falcon25>
    suspend fun getAllAddresses(): List<String>
    suspend fun getAccount(address: String): LocalAccount.Falcon25?
    suspend fun addAccount(
        account: LocalAccount.Falcon25,
        privateKey: ByteArray,
        entropy: ByteArray,
    )
    suspend fun deleteAccount(address: String)
    suspend fun deleteAllAccounts()
    suspend fun getPrivateKey(address: String): ByteArray?
    suspend fun getEntropy(address: String): ByteArray?
}
