package com.michaeltchuang.walletsdk.service

/**
 * Constants for wallet UI screens.
 * These correspond to AlgoKitScreens in wallet-sdk-ui.
 * 
 * Client apps should copy this file or use these constants directly.
 */
object WalletScreens {
    /**
     * Account type selection screen (Algo25, HD, etc.)
     */
    const val ONBOARDING = "ONBOARDING"
    
    /**
     * Settings screen (network config, developer settings)
     */
    const val SETTINGS = "SETTINGS"
    
    /**
     * Account list screen
     */
    const val ACCOUNT_LIST = "ACCOUNT_LIST"
    
    /**
     * Account details screen (requires account address extra)
     */
    const val ACCOUNT_DETAILS = "ACCOUNT_DETAILS"
}
