package com.michaeltchuang.walletsdk.core.foundation.di

import com.michaeltchuang.walletsdk.core.foundation.database.AlgoKitDatabase
import com.michaeltchuang.walletsdk.core.foundation.database.getAlgoKitDatabase
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformKoinModule(): Module =
    module {
        single<AlgoKitDatabase> { getAlgoKitDatabase(get()) }

        // Include all Android-specific modules
        includes(
            com.michaeltchuang.walletsdk.core.foundation.utils.date.dateModule,
            com.michaeltchuang.walletsdk.core.encryption.di.encryptionModule,
            com.michaeltchuang.walletsdk.core.account.di.accountCoreModule,
            com.michaeltchuang.walletsdk.core.account.di.localAccountsModule,
            com.michaeltchuang.walletsdk.core.account.di.accountCustomInfoModule,
            com.michaeltchuang.walletsdk.core.passkeys.di.passkeyModule,
            com.michaeltchuang.walletsdk.core.passkeys.validator.di.validationModule,
        )
    }
