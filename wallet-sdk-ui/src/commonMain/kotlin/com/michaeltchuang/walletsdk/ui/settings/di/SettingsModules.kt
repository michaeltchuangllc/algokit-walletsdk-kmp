package com.michaeltchuang.walletsdk.ui.settings.di

import com.michaeltchuang.walletsdk.ui.settings.viewmodels.DeveloperSettingsViewModel
import com.michaeltchuang.walletsdk.ui.settings.viewmodels.HDWalletSelectionViewModel
import com.michaeltchuang.walletsdk.ui.settings.viewmodels.LanguageSelectorViewModel
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
        },
    )
