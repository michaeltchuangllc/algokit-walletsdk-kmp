package com.michaeltchuang.walletsdk.core.foundation.di

import com.michaeltchuang.walletsdk.core.foundation.database.AlgoKitDatabase
import com.michaeltchuang.walletsdk.core.foundation.database.createAlgoKitDatabase
import com.michaeltchuang.walletsdk.core.passkeys.di.passkeyModule
import org.koin.core.module.Module
import org.koin.dsl.module

// Variable to store app group directory (set before Koin initialization)
private var sharedAppGroupDirectory: String? = null

/**
 * Set the app group directory for database sharing between app and extensions.
 * MUST be called BEFORE initializing Koin.
 */
fun setSharedAppGroupDirectory(directory: String) {
    sharedAppGroupDirectory = directory
}

internal actual fun platformKoinModule(): Module =
    module {
        single<AlgoKitDatabase> {
            createAlgoKitDatabase(customDirectory = sharedAppGroupDirectory).build()
        }
        includes(
            com.michaeltchuang.walletsdk.core.account.di.solanaAccountModule,
            passkeyModule,
        )
    }
