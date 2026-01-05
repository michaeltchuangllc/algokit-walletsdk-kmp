package com.michaeltchuang.walletsdk.ui.liquidAuth.di

import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.LiquidAuthViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

internal val liquidAuthModules =
    listOf(
        module {
            viewModel {
                LiquidAuthViewModel(
                    nameRegistrationUseCase = get(),
                    getBasicAccountInformationUseCase = get(),
                    stateDelegate = get(),
                    eventDelegate = get(),
                )
            }
        },
    )
