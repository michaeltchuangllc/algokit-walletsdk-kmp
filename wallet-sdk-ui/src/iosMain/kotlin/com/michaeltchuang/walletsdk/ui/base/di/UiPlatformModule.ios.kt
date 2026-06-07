package com.michaeltchuang.walletsdk.ui.base.di

import com.michaeltchuang.walletsdk.ui.liquidStream.IosViewerPaymentOrchestrator
import com.michaeltchuang.walletsdk.ui.test.IosPaymentTestViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

actual fun uiPlatformModule(): Module =
    module {
        viewModel {
            IosPaymentTestViewModel(
                getLocalAccount = get(),
                getAlgo25SecretKey = get(),
                getFalcon24SecretKey = get(),
                getHdSeed = get(),
            )
        }
        singleOf(::IosViewerPaymentOrchestrator)
    }
