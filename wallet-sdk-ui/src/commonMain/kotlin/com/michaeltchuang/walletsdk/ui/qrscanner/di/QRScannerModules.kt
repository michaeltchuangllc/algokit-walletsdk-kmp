package com.michaeltchuang.walletsdk.ui.qrscanner.di

import com.michaeltchuang.walletsdk.ui.qrscanner.viewmodels.QRScannerViewModel
import com.michaeltchuang.walletsdk.ui.signing.viewmodels.TransactionSuccessViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

internal val qrScannerModules =
    listOf(
        module {
            viewModel {
                QRScannerViewModel(
                    get(),
                    get(),
                )
            }

            viewModel {
                TransactionSuccessViewModel(
                    get(),
                )
            }
        },
    )
