package com.michaeltchuang.walletsdk.core.foundation.di

import com.michaeltchuang.walletsdk.core.account.di.accountModules
import com.michaeltchuang.walletsdk.core.deeplink.di.deepLinkModules
import com.michaeltchuang.walletsdk.core.liquidAuth.di.liquidAuthDomainModule
import com.michaeltchuang.walletsdk.core.transaction.di.transactionModules

val walletSdkCoreModules =
    buildList {
        addAll(foundationModules)
        addAll(deepLinkModules)
        addAll(accountModules)
        addAll(transactionModules)
        add(liquidAuthDomainModule)
    }
