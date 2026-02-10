package com.michaeltchuang.walletsdk.service

import com.michaeltchuang.walletsdk.core.account.domain.model.custom.AccountLite
import com.michaeltchuang.walletsdk.core.network.model.AlgorandNetwork
import com.michaeltchuang.walletsdk.ui.initializeSdk.WalletSDK
import kotlinx.coroutines.flow.Flow

/**
 * WalletService provides a simplified service layer for Android applications
 * to interact with the AlgoKit Wallet SDK.
 *
 * This service wraps the WalletSDK functionality and provides a clean API
 * for account and network management operations.
 *
 * Usage:
 * ```
 * // Initialize the service in your Application class
 * WalletService.initialize(applicationContext)
 *
 * // Use the service in your ViewModels or repositories
 * val accounts = WalletService.getAccountsWithBalances()
 * WalletService.deleteAccount(address)
 * ```
 */
object WalletService {
    
    /**
     * Initialize the WalletSDK.
     * This should be called in your Application class onCreate() method.
     *
     * @param context Application context
     * @param enableLogging Whether to enable SDK logging (default: false)
     */
    fun initialize(
        context: Any? = null,
        enableLogging: Boolean = false
    ) {
        WalletSDK.initialize(context, enableLogging)
    }
    
    /**
     * Get all accounts with their current balances.
     * This operation fetches account information from the network.
     *
     * @return List of AccountLite objects containing account addresses and balances
     * @throws Exception if the operation fails
     */
    suspend fun getAccountsWithBalances(): List<AccountLite> {
        return WalletSDK.getAccountsWithBalances()
    }
    
    /**
     * Delete an account from the wallet.
     *
     * @param address The address of the account to delete
     * @throws Exception if the operation fails
     */
    suspend fun deleteAccount(address: String) {
        WalletSDK.deleteAccount(address)
    }
    
    /**
     * Get the current network configuration as a Flow.
     * This allows observing network changes reactively.
     *
     * @return Flow of AlgorandNetwork representing the current network
     */
    fun getCurrentNetwork(): Flow<AlgorandNetwork> {
        return WalletSDK.getCurrentNetwork()
    }
    
    /**
     * Get all accounts without balances (lighter operation).
     * Note: This is currently not exposed by WalletSDK but can be added if needed.
     *
     * @return List of AccountLite objects
     */
    // suspend fun getAccounts(): List<AccountLite> {
    //     // This could be implemented if needed by accessing the use case directly
    //     // For now, use getAccountsWithBalances()
    // }
}
