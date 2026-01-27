package com.michaeltchuang.walletsdk.demo

import androidx.compose.ui.window.ComposeUIViewController
import com.michaeltchuang.walletsdk.demo.di.initKoinConfig
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.mp.KoinPlatform

// iOS-specific implementations
object IosApp

/**
 * Create the main view controller for iOS app
 */
fun MainViewController() = ComposeUIViewController { App() }

/**
 * Set the app group directory for sharing data between app and extensions.
 * MUST be called BEFORE initializeKoin() to take effect.
 *
 * @param directory The path to the app group's shared container directory
 */
fun setAppGroupDirectory(directory: String) {
    // Forward to wallet-sdk-core
    com.michaeltchuang.walletsdk.core.foundation.di
        .setSharedAppGroupDirectory(directory)
}

/**
 * Initialize Koin for iOS app extensions (e.g., AutofillCredentialExtension).
 * Call this function before accessing any Koin dependencies from Swift.
 *
 * Note: This should only be called once per process. If Koin is already started,
 * this will stop and restart it.
 */
fun initializeKoin() {
    try {
        // Stop existing Koin instance if it exists
        stopKoin()
    } catch (e: Exception) {
        // Koin wasn't started, ignore
    }

    startKoin(initKoinConfig)
}

/**
 * Get the Koin instance for dependency injection.
 * Call initializeKoin() first before using this.
 */
fun getKoin(): Koin = KoinPlatform.getKoin()

/**
 * Get PasskeyRepository instance from Koin.
 * Call initializeKoin() first before using this.
 */
fun getPasskeyRepository(): com.michaeltchuang.walletsdk.core.passkeys.domain.repository.PasskeyRepository = KoinPlatform.getKoin().get()

/**
 * Get all HD seed first addresses from the database (synchronous for iOS).
 * Call initializeKoin() first before using this.
 * Throws an exception if the operation fails.
 */
@Throws(Exception::class)
fun getAllHdSeedFirstAddresses(): List<com.michaeltchuang.walletsdk.core.account.domain.model.local.HdSeedFirstAddress> {
    val useCase: com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAllHdSeedFirstAddresses = 
        KoinPlatform.getKoin().get()
    return kotlinx.coroutines.runBlocking {
        useCase()
    }
}

/**
 * Get the mnemonic for a given account address (synchronous for iOS).
 * Call initializeKoin() first before using this.
 * Returns AccountMnemonic or throws an exception if not found.
 */
@Throws(Exception::class)
fun getAccountMnemonic(address: String): com.michaeltchuang.walletsdk.core.account.domain.model.local.AccountMnemonic {
    val useCase: com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAccountMnemonic = 
        KoinPlatform.getKoin().get()
    
    return kotlinx.coroutines.runBlocking {
        when (val result = useCase(address)) {
            is com.michaeltchuang.walletsdk.core.foundation.WalletSdkResult.Success -> result.data
            is com.michaeltchuang.walletsdk.core.foundation.WalletSdkResult.Error -> 
                throw result.exception
        }
    }
}
