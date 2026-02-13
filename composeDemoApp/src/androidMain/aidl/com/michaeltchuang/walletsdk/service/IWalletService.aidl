package com.michaeltchuang.walletsdk.service;

/**
 * AIDL interface for the Wallet Service.
 * 
 * This interface defines the contract between the wallet service and client apps.
 * Client apps bind to this service to access wallet functionality.
 */
interface IWalletService {
    
    /**
     * Check if the service is ready to handle requests.
     * Always call this before using other methods.
     */
    boolean isServiceReady();
    
    /**
     * Get all accounts with their balances as a JSON string.
     * Returns: JSON array of AccountLite objects
     */
    String getAccountsWithBalances();
    
    /**
     * Delete an account by its address.
     * @param address The account address to delete
     * @return true if successful, false otherwise
     */
    boolean deleteAccount(String address);
    
    /**
     * Get the current network name (MainNet, TestNet, etc.)
     */
    String getCurrentNetwork();
    
    /**
     * Get the class name for the wallet UI activity.
     * Client app should launch this activity directly.
     * @return Fully qualified activity class name
     */
    String getWalletUIActivityClass();
}
