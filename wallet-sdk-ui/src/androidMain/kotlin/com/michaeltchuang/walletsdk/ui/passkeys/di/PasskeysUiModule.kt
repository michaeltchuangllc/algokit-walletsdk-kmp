@file:Suppress("NewApi")

package com.michaeltchuang.walletsdk.ui.passkeys.di

import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.passkeys.mapper.CreatePasskeyParamsMapper
import com.michaeltchuang.walletsdk.core.passkeys.mapper.CreatePublicKeyCredentialResponseArgsMapper
import com.michaeltchuang.walletsdk.core.passkeys.mapper.DefaultCreatePublicKeyCredentialResponseArgsMapper
import com.michaeltchuang.walletsdk.core.passkeys.validator.CreatePasskeyIntentValidator
import com.michaeltchuang.walletsdk.core.passkeys.validator.GetPasskeyIntentValidator
import com.michaeltchuang.walletsdk.ui.liquidAuth.AnswerViewModel
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
                getAlgo25SecretKey = get(),
                getFalcon24SecretKey = get(),
                getAccountDetail = get(),
                getSeed = get(),
            )
        }
    }
