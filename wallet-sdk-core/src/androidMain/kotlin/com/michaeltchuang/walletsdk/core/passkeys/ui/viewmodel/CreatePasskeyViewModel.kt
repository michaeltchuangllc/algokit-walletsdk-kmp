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

package com.michaeltchuang.walletsdk.core.passkeys.ui.viewmodel

import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.provider.CallingAppInfo
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.passkeys.domain.model.PublicKeyCredentialCreationOptions
import com.michaeltchuang.walletsdk.core.passkeys.domain.usecase.AddNewPasskey
import com.michaeltchuang.walletsdk.core.passkeys.ui.mapper.CreatePublicKeyCredentialResponseArgsMapper
import com.michaeltchuang.walletsdk.core.passkeys.ui.model.CreatePasskeyIntentValidationResult.AppInfoNotFound
import com.michaeltchuang.walletsdk.core.passkeys.ui.model.CreatePasskeyIntentValidationResult.BiometricError
import com.michaeltchuang.walletsdk.core.passkeys.ui.model.CreatePasskeyIntentValidationResult.ExistingPasskey
import com.michaeltchuang.walletsdk.core.passkeys.ui.model.CreatePasskeyIntentValidationResult.FailedToValidateOrigin
import com.michaeltchuang.walletsdk.core.passkeys.ui.model.CreatePasskeyIntentValidationResult.FailedToValidateRP
import com.michaeltchuang.walletsdk.core.passkeys.ui.model.CreatePasskeyIntentValidationResult.InvalidRequestType
import com.michaeltchuang.walletsdk.core.passkeys.ui.model.CreatePasskeyIntentValidationResult.Success
import com.michaeltchuang.walletsdk.core.passkeys.ui.model.CreatePasskeyIntentValidationResult.UnableToExtractData
import com.michaeltchuang.walletsdk.core.passkeys.validator.CreatePasskeyIntentValidator
import kotlinx.coroutines.launch


@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
internal class CreatePasskeyViewModel(
    private val eventDelegate: EventDelegate<ViewEvent>,
    private val addNewPasskey: AddNewPasskey,
    private val createPublicKeyCredentialResponseProcessor: CreatePublicKeyCredentialResponseProcessor,
    private val createPublicKeyCredentialResponseArgsMapper: CreatePublicKeyCredentialResponseArgsMapper,
    private val createPasskeyIntentValidator: CreatePasskeyIntentValidator
) : ViewModel(), EventViewModel<CreatePasskeyViewModel.ViewEvent> by eventDelegate {

    fun processIntent(intent: Intent) {
        viewModelScope.launch {
            when (val result = createPasskeyIntentValidator.validate(intent)) {
                AppInfoNotFound -> finishWithError("Calling app info not found")
                FailedToValidateOrigin -> finishWithError("Failed to validate origin")
                FailedToValidateRP -> finishWithError("Failed to validate relying party")
                InvalidRequestType -> finishWithError("Unexpected create request found")
                UnableToExtractData -> finishWithError("Unable to extract data")
                ExistingPasskey -> finishWithError("This passkey already exists")
                is BiometricError -> finishWithBiometricError(result)
                is Success -> {
                    if (result.request.biometricPromptResult?.isSuccessful == true) {
                        createPasskey(result.params)
                    } else {
                        eventDelegate.sendEvent(
                            ViewEvent.AuthenticateCreatePasskeyWithBiometrics(
                                result.params
                            )
                        )
                    }
                }
            }
        }
    }

    private suspend fun finishWithError(errorMessage: String) {
        eventDelegate.sendEvent(ViewEvent.FinishActivityWithCreateError(errorMessage))
    }

    private suspend fun finishWithBiometricError(result: BiometricError) {
        eventDelegate.sendEvent(ViewEvent.FinishActivityWithCreateBiometricError(result.code, result.message))
    }

    fun createPasskey(params: CreatePasskeyParams) {
        viewModelScope.launch {
            with(params) {
                val args = createPublicKeyCredentialResponseArgsMapper(params, appInfoOrigin)
                val responseData = createPublicKeyCredentialResponseProcessor(args)
                addNewPasskey(bip44Address, requestOptions, responseData.credentialId)
                eventDelegate.sendEvent(ViewEvent.SetCreateResponseAndFinishActivity(responseData.response))
            }
        }
    }

    sealed interface ViewEvent {
        data class FinishActivityWithCreateError(val errorMessage: String) : ViewEvent
        data class FinishActivityWithCreateBiometricError(val errorCode: Int, val errorMessage: String) : ViewEvent
        data class AuthenticateCreatePasskeyWithBiometrics(val params: CreatePasskeyParams) : ViewEvent
        data class SetCreateResponseAndFinishActivity(val response: CreatePublicKeyCredentialResponse) : ViewEvent
    }

    data class CreatePasskeyParams(
        val requestOptions: PublicKeyCredentialCreationOptions,
        val callingAppInfo: CallingAppInfo,
        val clientDataHash: ByteArray?,
        val bip44Address: String,
        val appInfoOrigin: String
    ) {
        val rpId: String
            get() = requestOptions.rp.id
    }
}
