package com.michaeltchuang.walletsdk.service;

/**
 * AIDL interface for the Wallet Service.
 * This interface defines the methods that client apps can call.
 */
interface IWalletService {
    /**
     * Get all accounts with their balances as JSON string.
     * Returns a JSON array of AccountLite objects.
     */
    String getAccountsWithBalances();
    
    /**
     * Delete an account by address.
     * @param address The account address to delete
     * @return true if successful, false otherwise
     */
    boolean deleteAccount(String address);
    
    /**
     * Get the current network configuration as JSON string.
     * Returns a JSON object representing AlgorandNetwork.
     */
    String getCurrentNetwork();
    
    /**
     * Check if the service is ready and initialized.
     * @return true if service is ready to handle requests
     */
    boolean isServiceReady();
}
