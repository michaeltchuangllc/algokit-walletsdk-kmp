package com.michaeltchuang.walletsdk.ui.signing.di

import com.michaeltchuang.walletsdk.ui.signing.viewmodels.AddAssetViewModel
import com.michaeltchuang.walletsdk.ui.signing.viewmodels.AssetTransferConfirmViewModel
import com.michaeltchuang.walletsdk.ui.signing.viewmodels.KeyRegConfirmViewModel
import com.michaeltchuang.walletsdk.ui.signing.viewmodels.SelectAccountViewModel
import com.michaeltchuang.walletsdk.ui.signing.viewmodels.SelectReceiverViewModel
import com.michaeltchuang.walletsdk.ui.signing.viewmodels.SendAlgoViewModel
import com.michaeltchuang.walletsdk.ui.signing.viewmodels.TransactionSuccessViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

internal val signingModules =
    listOf(
        module {
            viewModel {
                KeyRegConfirmViewModel(
                    get(),
                    get(),
                    get(),
                    get(),
                    get(),
                    get(),
                )
            }

            viewModel {
                TransactionSuccessViewModel(
                    get(),
                )
            }

            viewModel {
                AssetTransferConfirmViewModel(
                    get(),
                    get(),
                    get(),
                    get(),
                    get(),
                    get(),
                    get(),
                    get(),
                    get(),
                )
            }

            viewModel {
                SelectAccountViewModel(
                    get(),
                    get(),
                    get(),
                    get(),
                )
            }

            viewModel {
                SendAlgoViewModel(
                    get(),
                    get(),
                    get(),
                    get(),
                    get(),
                )
            }
            viewModel {
                SelectReceiverViewModel(
                    get(),
                    get(),
                    get(),
                )
            }

            viewModel {
                AddAssetViewModel(
                    get(),
                    get(),
                    get(),
                    get(),
                    get(),
                    get(),
                    get(),
                )
            }
        },
    )
