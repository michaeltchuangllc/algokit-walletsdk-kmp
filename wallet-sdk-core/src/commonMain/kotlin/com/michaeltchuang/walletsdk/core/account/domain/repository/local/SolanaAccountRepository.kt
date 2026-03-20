package com.michaeltchuang.walletsdk.core.account.domain.repository.local

import com.michaeltchuang.walletsdk.core.account.domain.model.local.SolanaAccount
import kotlinx.coroutines.flow.Flow

/**
 * Repository for managing locally stored Seed Vault accounts.
 * Contains public_key as primary key (address), address string, and chainId.
 */
interface SolanaAccountRepository {
    fun getAllAsFlow(): Flow<List<SolanaAccount>>

    fun getAccountCountAsFlow(): Flow<Int>

    suspend fun getAccountCount(): Int

    suspend fun getAll(): List<SolanaAccount>

    suspend fun getAllAddresses(): List<String>

    suspend fun getAccount(publicKey: String): SolanaAccount?

    suspend fun addAccount(account: SolanaAccount)

    suspend fun addAccounts(accounts: List<SolanaAccount>)

    suspend fun deleteAccount(publicKey: String)
    
    suspend fun deleteAccountByAddress(address: String)

    suspend fun isAddressExists(address: String): Boolean
    
    suspend fun updateAccountNameByAddress(address: String, accountName: String?)

    suspend fun deleteAllAccounts()
}
