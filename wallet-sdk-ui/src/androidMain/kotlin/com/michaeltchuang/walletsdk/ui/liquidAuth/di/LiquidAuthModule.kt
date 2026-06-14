package com.michaeltchuang.walletsdk.ui.liquidAuth.di

import com.algorand.algosdk.transaction.Transaction
import com.algorand.algosdk.util.Encoder
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAccountMnemonic
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.usecases.AssertionApiUseCase
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.usecases.AttestationApiUseCase
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.usecases.AuthenticateWithBiometricsUseCase
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.usecases.LogAppSignatureUseCase
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.usecases.ManageSignalServiceUseCase
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.usecases.ProcessSignTransactionsUseCase
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.usecases.ProvideCookieJarUseCase
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.usecases.ProvideHttpClientUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases.AssertionIntentLauncherUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases.AttestationIntentLauncherUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases.HandleAssertionResultUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases.HandleAttestationResultUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases.PrepareAuthenticationUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases.ProcessBiometricTransactionSigningUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases.RegisterPasskeyUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases.SetupMppPaymentViewerUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.AnswerViewModel
import com.michaeltchuang.walletsdk.ui.settings.viewmodels.EscrowSessionVaultDebugViewModel
import org.koin.androidx.viewmodel.dsl.viewModel

import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import kotlin.io.encoding.Base64

val liquidAuthUIModule =
    module {
        // Use Cases
        singleOf(::ProvideCookieJarUseCase)
        single { ProvideHttpClientUseCase(get()) }
        singleOf(::ManageSignalServiceUseCase)
        singleOf(::AuthenticateWithBiometricsUseCase)
        singleOf(::RegisterPasskeyUseCase)
        singleOf(::HandleAttestationResultUseCase)
        singleOf(::HandleAssertionResultUseCase)
        singleOf(::PrepareAuthenticationUseCase)
        single { ProcessBiometricTransactionSigningUseCase(get()) }
        single { SetupMppPaymentViewerUseCase(get()) }
        singleOf(::LogAppSignatureUseCase)
        single {
            val getMnemonic: suspend (String) -> String? = { algoAddr ->
                get<GetAccountMnemonic>()
                    .invoke(algoAddr)
                    .getDataOrNull()
                    ?.words
                    ?.joinToString(" ")
            }
            val decodeUnsignedTransaction: (String) -> Transaction? = { s ->
                Encoder.decodeFromMsgPack(
                    Base64.decode(s),
                    Transaction::class.java,
                )
            }
            ProcessSignTransactionsUseCase(
                getLocalAccount = get(),
                getAlgo25SecretKey = get(),
                getFalcon24SecretKey = get(),
                getSeed = get(),
                getMnemonic = getMnemonic,
                decodeUnsignedTransaction = decodeUnsignedTransaction,
            )
        }
        single { AttestationIntentLauncherUseCase(get()) }
        single { AssertionIntentLauncherUseCase(get()) }
        single { AttestationApiUseCase(get<ProvideHttpClientUseCase>()()) }
        single { AssertionApiUseCase(get<ProvideHttpClientUseCase>()()) }

        viewModel {
            EscrowSessionVaultDebugViewModel(
                get(),
            )
        }

        viewModel {
            AnswerViewModel(
                addNewPasskey = get(),
                passkeyRepository = get(),
                setPasskeyLastUsedTime = get(),
                getAccountMnemonic = get(),
                getAlgo25SecretKey = get(),
                timeProvider = get(),
                getFalcon24SecretKey = get(),
                getLocalAccount = get(),
                getLocalAccounts = get(),
                getSeed = get(),
                processBiometricTransactionSigningUseCase = get(),
                registerPasskeyUseCase = get(),
                prepareAuthenticationUseCase = get(),
                manageSignalServiceUseCase = get(),
                eventDelegate = get(),
                processSignTransactionsUseCase = get(),
                attestationIntentLauncherUseCase = get(),
                assertionIntentLauncherUseCase = get(),
                logAppSignatureUseCase = get(),
                providerHttpClientUseCase = get(),
                getAccountAlgoBalance = get(),
                getCurrentBlockUseCase = get(),
                setupMppPaymentViewerUseCase = get(),
                getRemainingSessionVaultBalanceUseCase = get(),
            )
        }
    }
