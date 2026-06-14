package com.michaeltchuang.walletsdk.ui.base.di

import com.michaeltchuang.walletsdk.ui.liquidStream.IosViewerPaymentOrchestrator
import com.michaeltchuang.walletsdk.ui.settings.viewmodels.EscrowSessionVaultDebugViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

actual fun uiPlatformModule(): Module =
    module {
        viewModel {
            EscrowSessionVaultDebugViewModel(
                mppWalletSignerUseCase = get(),
            )
        }
        singleOf(::IosViewerPaymentOrchestrator)
    }
