package com.michaeltchuang.walletsdk.core.transaction.di

import com.michaeltchuang.walletsdk.core.foundation.utils.TransactionSignSigningHelper
import com.michaeltchuang.walletsdk.core.transaction.signmanager.TransactionSignManager
import org.koin.dsl.module

val assetTransferModule =
    module {
        factory { TransactionSignSigningHelper() }
        factory {
            TransactionSignManager(
                getTransactionParams = get(),
                signHelper = get(),
                getFalcon24SecretKey = get(),
                getFalcon25Entropy = get(),
                getAlgo25SecretKey = get(),
                getHdSeed = get(),
                getLocalAccount = get(),
            )
        }
    }
