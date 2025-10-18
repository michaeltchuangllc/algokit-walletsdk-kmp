package com.michaeltchuang.walletsdk.ui.onboarding.di

import com.michaeltchuang.walletsdk.ui.onboarding.viewmodels.CreateAccountNameViewModel
import com.michaeltchuang.walletsdk.ui.onboarding.viewmodels.Falcon24WalletSelectionViewModel
import com.michaeltchuang.walletsdk.ui.onboarding.viewmodels.OnboardingAccountTypeViewModel
import com.michaeltchuang.walletsdk.ui.onboarding.viewmodels.OnboardingIntroViewModel
import com.michaeltchuang.walletsdk.ui.onboarding.viewmodels.RecoverPassphraseViewModel
import com.michaeltchuang.walletsdk.ui.settings.viewmodels.HDWalletSelectionViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

internal val onboardingModules =
    listOf(
        module {
            viewModel {
                OnboardingIntroViewModel(
                    get(),
                    get(),
                    get(),
                    get(),
                )
            }
            viewModel {
                OnboardingAccountTypeViewModel(
                    get(),
                    get(),
                    get(),
                    get(),
                )
            }

            viewModel {
                CreateAccountNameViewModel(
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
                RecoverPassphraseViewModel(get(), get())
            }
            viewModel {
                Falcon24WalletSelectionViewModel(get(), get(), get(), get(), get())
            }
        },
    )
