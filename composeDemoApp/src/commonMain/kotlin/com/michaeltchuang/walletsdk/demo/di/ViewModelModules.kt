package com.michaeltchuang.walletsdk.demo.di

import com.michaeltchuang.walletsdk.demo.ui.viewmodel.AccountListViewModel
import com.michaeltchuang.walletsdk.demo.ui.viewmodel.AppViewModel
import com.michaeltchuang.walletsdk.demo.ui.widgets.snackbar.SnackbarViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val provideViewModelModules =
    module {
        single { SnackbarViewModel() }
        viewModel<AppViewModel> {
            AppViewModel()
        }
        viewModel<AccountListViewModel> {
            AccountListViewModel(
                stateDelegate = get(),
                eventDelegate = get(),
            )
        }
    }
