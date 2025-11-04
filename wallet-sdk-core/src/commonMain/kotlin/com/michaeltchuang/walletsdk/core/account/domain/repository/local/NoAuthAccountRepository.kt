package com.michaeltchuang.walletsdk.core.account.domain.repository.local

import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import kotlinx.coroutines.flow.Flow

interface NoAuthAccountRepository {
    fun getAllAsFlow(): Flow<List<LocalAccount.NoAuth>>

    fun getAccountCountAsFlow(): Flow<Int>

    suspend fun getAccountCount(): Int

    suspend fun getAll(): List<LocalAccount.NoAuth>

    suspend fun getAllAddresses(): List<String>

    suspend fun getAccount(address: String): LocalAccount.NoAuth?

    suspend fun addAccount(account: LocalAccount.NoAuth)

    suspend fun deleteAccount(address: String)

    suspend fun isAddressExists(address: String): Boolean

    suspend fun deleteAllAccounts()
}
