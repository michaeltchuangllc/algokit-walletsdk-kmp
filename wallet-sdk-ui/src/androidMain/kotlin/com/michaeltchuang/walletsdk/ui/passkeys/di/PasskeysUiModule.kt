@file:Suppress("NewApi")

package com.michaeltchuang.walletsdk.ui.passkeys.di

import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAccountMnemonic
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.passkeys.mapper.CreatePasskeyParamsMapper
import com.michaeltchuang.walletsdk.core.passkeys.mapper.CreatePublicKeyCredentialResponseArgsMapper
import com.michaeltchuang.walletsdk.core.passkeys.mapper.DefaultCreatePublicKeyCredentialResponseArgsMapper
import com.michaeltchuang.walletsdk.core.passkeys.validator.CreatePasskeyIntentValidator
import com.michaeltchuang.walletsdk.core.passkeys.validator.GetPasskeyIntentValidator
import com.michaeltchuang.walletsdk.ui.liquidAuth.AnswerViewModel
import com.michaeltchuang.walletsdk.ui.liquidAuth.usecases.AssertionApiUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.usecases.AssertionIntentLauncherUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.usecases.AttestationApiUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.usecases.AttestationIntentLauncherUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.usecases.AuthenticateWithBiometricsUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.usecases.HandleAssertionResultUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.usecases.HandleAttestationResultUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.usecases.LogAppSignatureUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.usecases.ManageSignalServiceUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.usecases.PrepareAuthenticationUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.usecases.ProcessBiometricTransactionSigningUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.usecases.ProcessSignTransactionsUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.usecases.ProvideCookieJarUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.usecases.ProvideHttpClientUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.usecases.RegisterPasskeyUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.usecases.ShowTransactionConfirmationDialogUseCase
import com.michaeltchuang.walletsdk.ui.passkeys.DefaultCreatePasskeyIntentValidator
import com.michaeltchuang.walletsdk.ui.passkeys.DefaultGetPasskeyIntentValidator
import com.michaeltchuang.walletsdk.ui.passkeys.mapper.DefaultCreatePasskeyParamsMapper
import com.michaeltchuang.walletsdk.ui.passkeys.viewmodel.CreatePasskeyViewModel
import com.michaeltchuang.walletsdk.ui.passkeys.viewmodel.GetPasskeyViewModel
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val passkeysUiModule =
    module {
        // Mappers
        singleOf(::DefaultCreatePasskeyParamsMapper) bind CreatePasskeyParamsMapper::class
        singleOf(::DefaultCreatePublicKeyCredentialResponseArgsMapper) bind CreatePublicKeyCredentialResponseArgsMapper::class

        // Validators
        singleOf(::DefaultCreatePasskeyIntentValidator) bind CreatePasskeyIntentValidator::class
        singleOf(::DefaultGetPasskeyIntentValidator) bind GetPasskeyIntentValidator::class

        // Use Cases
        singleOf(::ProvideCookieJarUseCase)
        single { ProvideHttpClientUseCase(get()) }
        singleOf(::ManageSignalServiceUseCase)
        singleOf(::AuthenticateWithBiometricsUseCase)
        singleOf(::ShowTransactionConfirmationDialogUseCase)
        singleOf(::RegisterPasskeyUseCase)
        singleOf(::HandleAttestationResultUseCase)
        singleOf(::HandleAssertionResultUseCase)
        singleOf(::PrepareAuthenticationUseCase)
        single { ProcessBiometricTransactionSigningUseCase(get()) }
        singleOf(::LogAppSignatureUseCase)
        single {
            val getMnemonic: suspend (String) -> String? = { algoAddr ->
                get<GetAccountMnemonic>().invoke(algoAddr).getDataOrNull()?.words?.joinToString(" ")
            }
            val decodeUnsignedTransaction: (String) -> com.algorand.algosdk.transaction.Transaction? = { s ->
                com.algorand.algosdk.util.Encoder.decodeFromMsgPack(
                    kotlin.io.encoding.Base64.decode(s),
                    com.algorand.algosdk.transaction.Transaction::class.java
                )
            }
            ProcessSignTransactionsUseCase(
                getLocalAccount = get(),
                getAlgo25SecretKey = get(),
                getFalcon24SecretKey = get(),
                getSeed = get(),
                getMnemonic = getMnemonic,
                decodeUnsignedTransaction = decodeUnsignedTransaction
            )
        }
        single { AttestationIntentLauncherUseCase(get()) }
        single { AssertionIntentLauncherUseCase(get()) }
        single { AttestationApiUseCase(get<ProvideHttpClientUseCase>()()) }
        single { AssertionApiUseCase(get<ProvideHttpClientUseCase>()()) }


        // ViewModels
        viewModel {
            CreatePasskeyViewModel(
                eventDelegate = EventDelegate(),
                addNewPasskey = get(),
                createPublicKeyCredentialResponseProcessor = get(),
                createPublicKeyCredentialResponseArgsMapper = get(),
                createPasskeyIntentValidator = get(),
            )
        }

        viewModel {
            GetPasskeyViewModel(
                eventDelegate = EventDelegate(),
                getCredentialResponseProcessor = get(),
                getPasskeyIntentValidator = get(),
            )
        }
        viewModel {
            AnswerViewModel(
                addNewPasskey = get(),
                passkeyRepository = get(),
                setPasskeyLastUsedTime = get(),
                getAccountMnemonic = get(),
                timeProvider = get(),
                getFalcon24SecretKey = get(),
                getLocalAccount = get(),
                getSeed = get(),
                showTransactionConfirmationDialogUseCase = get(),
                processBiometricTransactionSigningUseCase = get(),
                registerPasskeyUseCase = get(),
                prepareAuthenticationUseCase = get(),
                manageSignalServiceUseCase = get(),
                eventDelegate = get(),
                processSignTransactionsUseCase = get(),
                attestationIntentLauncherUseCase = get(),
                assertionIntentLauncherUseCase = get(),
                attestationApiUseCase = get(),
                assertionApiUseCase = get(),
                logAppSignatureUseCase = get(),
                providerHttpClientUseCase = get()
            )
        }
    }
