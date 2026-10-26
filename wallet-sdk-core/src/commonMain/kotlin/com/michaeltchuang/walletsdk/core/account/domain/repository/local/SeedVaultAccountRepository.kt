package com.michaeltchuang.walletsdk.core.account.domain.repository.local

import com.michaeltchuang.walletsdk.core.account.domain.model.local.SeedVaultAccount
import kotlinx.coroutines.flow.Flow

/**
 * Repository for managing locally stored Seed Vault accounts.
 * Contains public_key as primary key (address), address string, and chainId.
 */
interface SeedVaultAccountRepository {
    fun getAllAsFlow(): Flow<List<SeedVaultAccount>>

    fun getAccountCountAsFlow(): Flow<Int>

    suspend fun getAccountCount(): Int

    suspend fun getAll(): List<SeedVaultAccount>

    suspend fun getAllAddresses(): List<String>

    suspend fun getAccount(publicKey: String): SeedVaultAccount?

    suspend fun addAccount(account: SeedVaultAccount)

    suspend fun addAccounts(accounts: List<SeedVaultAccount>)

    suspend fun deleteAccount(publicKey: String)

    suspend fun deleteAccountByAddress(address: String)

    suspend fun isAddressExists(address: String): Boolean

    suspend fun updateAccountNameByAddress(
        address: String,
        accountName: String?,
    )

    suspend fun deleteAllAccounts()
}
