@file:Suppress("NewApi")
/*
 * Copyright 2022-2025 Pera Wallet, LDA
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.michaeltchuang.walletsdk.core.passkeys.di

import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.passkeys.ui.mapper.CreatePasskeyParamsMapper
import com.michaeltchuang.walletsdk.core.passkeys.ui.mapper.CreatePublicKeyCredentialResponseArgsMapper
import com.michaeltchuang.walletsdk.core.passkeys.ui.mapper.DefaultCreatePasskeyParamsMapper
import com.michaeltchuang.walletsdk.core.passkeys.ui.mapper.DefaultCreatePublicKeyCredentialResponseArgsMapper
import com.michaeltchuang.walletsdk.core.passkeys.ui.viewmodel.CreatePasskeyViewModel
import com.michaeltchuang.walletsdk.core.passkeys.ui.viewmodel.CreatePublicKeyCredentialResponseProcessor
import com.michaeltchuang.walletsdk.core.passkeys.ui.viewmodel.DefaultCreatePublicKeyCredentialResponseProcessor
import com.michaeltchuang.walletsdk.core.passkeys.ui.viewmodel.GetPasskeyViewModel
import com.michaeltchuang.walletsdk.core.passkeys.validator.CreatePasskeyIntentValidator
import com.michaeltchuang.walletsdk.core.passkeys.validator.DefaultCreatePasskeyIntentValidator
import com.michaeltchuang.walletsdk.core.passkeys.validator.DefaultGetPasskeyIntentValidator
import com.michaeltchuang.walletsdk.core.passkeys.validator.GetPasskeyIntentValidator
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val passkeysUiModule = module {
    // Mappers
    singleOf(::DefaultCreatePasskeyParamsMapper) bind CreatePasskeyParamsMapper::class
    singleOf(::DefaultCreatePublicKeyCredentialResponseArgsMapper) bind CreatePublicKeyCredentialResponseArgsMapper::class

    // Validators
    singleOf(::DefaultCreatePasskeyIntentValidator) bind CreatePasskeyIntentValidator::class
    singleOf(::DefaultGetPasskeyIntentValidator) bind GetPasskeyIntentValidator::class

    // Processors
    singleOf(::DefaultCreatePublicKeyCredentialResponseProcessor) bind CreatePublicKeyCredentialResponseProcessor::class

    // ViewModels
    viewModel {
        CreatePasskeyViewModel(
            eventDelegate = EventDelegate(),
            addNewPasskey = get(),
            createPublicKeyCredentialResponseProcessor = get(),
            createPublicKeyCredentialResponseArgsMapper = get(),
            createPasskeyIntentValidator = get()
        )
    }

    viewModel {
        GetPasskeyViewModel(
            eventDelegate = EventDelegate(),
            getCredentialResponseProcessor = get(),
            getPasskeyIntentValidator = get()
        )
    }
}
