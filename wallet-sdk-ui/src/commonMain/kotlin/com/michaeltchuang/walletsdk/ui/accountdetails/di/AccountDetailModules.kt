package com.michaeltchuang.walletsdk.ui.accountdetails.di

import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.ui.accountdetails.viewmodels.AccountDetailViewModel
import com.michaeltchuang.walletsdk.ui.accountdetails.viewmodels.QRCodeViewModel
import com.michaeltchuang.walletsdk.ui.accountdetails.viewmodels.ViewPassphraseViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

internal val accountDetailModules =
    listOf(
        module {
            viewModel {
                ViewPassphraseViewModel(get(), get(), get())
            }
            viewModel {
                AccountDetailViewModel(
                    get(),
                    get(),
                    get(),
                    get(),
                    get(),
                )
            }
            viewModel {
                QRCodeViewModel(
                    StateDelegate<QRCodeViewModel.ViewState>(),
                    EventDelegate<QRCodeViewModel.ViewEvent>(),
                )
            }
        },
    )
