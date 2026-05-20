package com.michaeltchuang.walletsdk.core.transaction.di

import com.michaeltchuang.walletsdk.core.transaction.signmanager.TransactionSignManager
import com.michaeltchuang.walletsdk.core.utils.TransactionSignSigningHelper
import org.koin.dsl.module

val assetTransferModule =
    module {
        factory { TransactionSignSigningHelper() }
        factory {
            TransactionSignManager(
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
            )
        }
    }
