package com.michaeltchuang.walletsdk.ui.settings.di

import com.michaeltchuang.walletsdk.ui.settings.utils.debug.LiquidStreamDebugBotRunner
import com.michaeltchuang.walletsdk.ui.settings.viewmodels.DeveloperSettingsViewModel
import com.michaeltchuang.walletsdk.ui.settings.viewmodels.HDWalletSelectionViewModel
import com.michaeltchuang.walletsdk.ui.settings.viewmodels.LanguageSelectorViewModel
import com.michaeltchuang.walletsdk.ui.settings.viewmodels.LiquidStreamLiveDebugViewModel
import com.michaeltchuang.walletsdk.ui.settings.viewmodels.LiquidStreamViewerDebugToolViewModel
import com.michaeltchuang.walletsdk.ui.settings.viewmodels.NodeSettingsViewModel
import com.michaeltchuang.walletsdk.ui.settings.viewmodels.PasskeysViewModel
import com.michaeltchuang.walletsdk.ui.settings.viewmodels.ThemePickerViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

expect fun platformModule(): Module

internal val settingsModules =
    listOf(
        platformModule(),
        module {
            viewModel {
                DeveloperSettingsViewModel(
                    get(),
                    get(),
                    get(),
                    get(),
                )
            }

            viewModel {
                HDWalletSelectionViewModel(
                    get(),
                    get(),
                    get(),
                    get(),
                    get(),
                )
            }

            viewModel {
                ThemePickerViewModel(
                    get(),
                    get(),
                    get(),
                )
            }

            viewModel {
                LanguageSelectorViewModel(
                    get(),
                    get(),
                    get(),
                )
            }

            viewModel {
                NodeSettingsViewModel(
                    get(),
                    get(),
                    get(),
                    get(),
                )
            }
            viewModel {
                PasskeysViewModel(get(), get(), get(), get())
            }

            viewModel {
                LiquidStreamViewerDebugToolViewModel(get(), get(), get())
            }

            single {
                LiquidStreamDebugBotRunner(
                    signerUseCase = get(),
                    channelSaltUseCase = get(),
                    voucherRepository = get(),
                    noteUseCase = get(),
                    httpClient = get(),
                    applicationScope = get(),
                )
            }
            viewModel {
                LiquidStreamLiveDebugViewModel(
                    selectionsUseCase = get(),
                    contextUseCase = get(),
                    getCurrentNetworkUseCase = get(),
                    runner = get(),
                    mppWalletSignerUseCase = get(),
                    applicationScope = get(),
                )
            }
        },
    )
