package com.michaeltchuang.walletsdk.ui.base.di

import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.ui.liquidAuth.service.AnswerPlatformServices
import com.michaeltchuang.walletsdk.ui.liquidStream.IosLiquidStreamViewerConnectionManager
import com.michaeltchuang.walletsdk.ui.liquidStream.domain.usecases.SetupMppPaymentViewerUseCase
import com.michaeltchuang.walletsdk.ui.settings.viewmodels.EscrowSessionVaultDebugViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

actual fun uiPlatformModule(): Module =
    module {
        viewModel {
            EscrowSessionVaultDebugViewModel(
                stateDelegate = StateDelegate<EscrowSessionVaultDebugViewModel.ViewState>(),
                eventDelegate = EventDelegate<EscrowSessionVaultDebugViewModel.ViewEvent>(),
                mppWalletSignerUseCase = get(),
                getLocalAccounts = get(),
                getSessionVaultContextUseCase = get(),
            )
        }
        factory { AnswerPlatformServices() }
        factory { IosLiquidStreamViewerConnectionManager(get()) }
        factory { SetupMppPaymentViewerUseCase(get(), get()) }
    }
