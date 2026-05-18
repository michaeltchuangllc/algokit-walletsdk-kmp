package com.michaeltchuang.walletsdk.ui.liquidAuth.di

import com.michaeltchuang.walletsdk.core.liquidAuth.domain.usecase.GenerateLiquidAuthOfferUseCase
import com.michaeltchuang.walletsdk.core.network.domain.usecase.GetCurrentNetworkUseCase
import com.michaeltchuang.walletsdk.core.network.usecase.GetCurrentBlockUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.LiquidAuthOfferViewModel
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.LiquidAuthViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

internal val liquidAuthModules =
    listOf(
        module {
            viewModel<LiquidAuthViewModel> {
                LiquidAuthViewModel(
                    nameRegistrationUseCase = get(),
                    getBasicAccountInformationUseCase = get(),
                    stateDelegate = get(),
                    eventDelegate = get(),
                )
            }
            viewModel<LiquidAuthOfferViewModel> {
                LiquidAuthOfferViewModel(
                    generateOfferUseCase = get<GenerateLiquidAuthOfferUseCase>(),
                    stateDelegate = get(),
                    eventDelegate = get(),
                    getAccountASABalance = get(),
                    getCurrentBlockUseCase = get<GetCurrentBlockUseCase>(),
                    getCurrentNetworkUseCase = get<GetCurrentNetworkUseCase>(),
                )
            }
        },
    )
