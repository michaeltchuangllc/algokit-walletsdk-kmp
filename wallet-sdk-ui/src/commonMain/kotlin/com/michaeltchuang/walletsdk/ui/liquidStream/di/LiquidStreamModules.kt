package com.michaeltchuang.walletsdk.ui.liquidStream.di

import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.ui.liquidStream.viewmodels.LiquidAuthViewerViewModel
import com.michaeltchuang.walletsdk.ui.liquidStream.viewmodels.LiquidStreamHostViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

internal val liquidStreamModules =
    listOf(
        module {
            viewModel {
                LiquidStreamHostViewModel(
                    stateDelegate = StateDelegate(),
                    eventDelegate = get(),
                )
            }
            viewModel {
                LiquidAuthViewerViewModel(
                    stateDelegate = StateDelegate(),
                    eventDelegate = get(),
                )
            }
        },
    )
