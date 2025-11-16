package com.michaeltchuang.walletsdk.core.account.di

val accountModules =
    listOf(
        accountCoreModule,
        localAccountsModule,
        accountCustomInfoModule,
        recoverRegisteredAccountsModule,
    )
