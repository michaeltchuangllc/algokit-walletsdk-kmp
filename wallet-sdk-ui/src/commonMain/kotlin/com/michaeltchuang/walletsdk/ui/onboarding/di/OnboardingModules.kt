package com.michaeltchuang.walletsdk.ui.onboarding.di

import com.michaeltchuang.walletsdk.ui.onboarding.viewmodels.AddressNamingViewModel
import com.michaeltchuang.walletsdk.ui.onboarding.viewmodels.CreateAccountNameViewModel
import com.michaeltchuang.walletsdk.ui.onboarding.viewmodels.CreateWatchAccountViewModel
import com.michaeltchuang.walletsdk.ui.onboarding.viewmodels.Falcon24WalletSelectionViewModel
import com.michaeltchuang.walletsdk.ui.onboarding.viewmodels.OnboardingAccountTypeViewModel
import com.michaeltchuang.walletsdk.ui.onboarding.viewmodels.OnboardingIntroViewModel
import com.michaeltchuang.walletsdk.ui.onboarding.viewmodels.RecoverPassphraseViewModel
import com.michaeltchuang.walletsdk.ui.onboarding.viewmodels.RecoverRegisteredAccountsViewModel
import com.michaeltchuang.walletsdk.ui.onboarding.viewmodels.SelectSeedViewModel
import com.michaeltchuang.walletsdk.ui.onboarding.viewmodels.SolanaAccountsViewModel
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
            viewModel {
                CreateWatchAccountViewModel(get(), get(), get())
            }

            viewModel {
                RecoverRegisteredAccountsViewModel(
                    accountAdditionUseCase = get(),
                    registeredAccountsProcessor = get(),
                    accountCreationHdKeyTypeMapper = get(),
                    stateDelegate = get(),
                    eventDelegate = get(),
                )
            }
            viewModel {
                AddressNamingViewModel(get(), get(), get(), get())
            }
            viewModel {
                SelectSeedViewModel(
                    stateDelegate = get(),
                    eventDelegate = get(),
                    getSolanaAccountsFromSeedVaultUseCase = get(),
                    seedVaultRepository = get(),
                )
            }
            viewModel {
                SolanaAccountsViewModel(
                    stateDelegate = get(),
                    eventDelegate = get(),
                    getSolanaAccountsFromSeedVaultUseCase = get(),
                    getImportedSolanaAddressesUseCase = get(),
                    importSolanaAccountsUseCase = get(),
                )
            }
        },
    )
