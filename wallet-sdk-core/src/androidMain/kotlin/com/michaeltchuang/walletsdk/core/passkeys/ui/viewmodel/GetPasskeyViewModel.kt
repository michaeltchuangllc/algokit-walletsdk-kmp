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
import androidx.credentials.GetCredentialResponse
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.passkeys.domain.model.PublicKeyCredentialRequestOptions
import com.michaeltchuang.walletsdk.core.passkeys.ui.model.GetPasskeyIntentValidationResult.AppInfoNotFound
import com.michaeltchuang.walletsdk.core.passkeys.ui.model.GetPasskeyIntentValidationResult.FailedToValidateOrigin
import com.michaeltchuang.walletsdk.core.passkeys.ui.model.GetPasskeyIntentValidationResult.FailedToValidateRP
import com.michaeltchuang.walletsdk.core.passkeys.ui.model.GetPasskeyIntentValidationResult.InvalidRequestType
import com.michaeltchuang.walletsdk.core.passkeys.ui.model.GetPasskeyIntentValidationResult.PasskeyNotFound
import com.michaeltchuang.walletsdk.core.passkeys.ui.model.GetPasskeyIntentValidationResult.Success
import com.michaeltchuang.walletsdk.core.passkeys.ui.model.GetPasskeyIntentValidationResult.UnableToExtractData
import com.michaeltchuang.walletsdk.core.passkeys.ui.viewmodel.GetPasskeyViewModel.ViewEvent
import com.michaeltchuang.walletsdk.core.passkeys.validator.GetPasskeyIntentValidator
import kotlinx.coroutines.launch


internal class GetPasskeyViewModel constructor(
    private val getPasskeyIntentValidator: GetPasskeyIntentValidator,
    private val getCredentialResponseProcessor: GetCredentialResponseProcessor,
    private val eventDelegate: EventDelegate<ViewEvent>
) : ViewModel(), EventViewModel<ViewEvent> by eventDelegate {

    fun processIntent(intent: Intent) {
        viewModelScope.launch {
            when (val result = getPasskeyIntentValidator.validate(intent)) {
                AppInfoNotFound -> finishWithError("Calling app info not found")
                FailedToValidateOrigin -> finishWithError("Failed to validate origin")
                FailedToValidateRP -> finishWithError("Failed to validate relying party")
                InvalidRequestType -> finishWithError("Unexpected create request found")
                UnableToExtractData -> finishWithError("Unable to extract data")
                PasskeyNotFound -> finishWithError("Requested credential not found")
                is Success -> {
                    if (result.request.biometricPromptResult?.isSuccessful == true) {
                        createGetCredentialResponse(result.params)
                    } else {
                        eventDelegate.sendEvent(
                            ViewEvent.AuthenticateGetPasskeyWithBiometrics(
                                result.params
                            )
                        )
                    }
                }
            }
        }
    }

    fun createGetCredentialResponse(params: GetCredentialsParams) {
        viewModelScope.launch {
            val response = getCredentialResponseProcessor.getResponseWithSignature(params)
            eventDelegate.sendEvent(ViewEvent.SetGetResponseAndFinishActivity(response))
        }
    }

    private suspend fun finishWithError(errorMessage: String) {
        eventDelegate.sendEvent(ViewEvent.FinishActivityWithGetError(errorMessage))
    }

    sealed interface ViewEvent {
        data class FinishActivityWithGetError(val errorMessage: String) : ViewEvent
        data class AuthenticateGetPasskeyWithBiometrics(val params: GetCredentialsParams) : ViewEvent
        data class SetGetResponseAndFinishActivity(val response: GetCredentialResponse) : ViewEvent
    }

    data class GetCredentialsParams(
        val bip44Address: String,
        val credId: String,
        val origin: String,
        val request: PublicKeyCredentialRequestOptions,
        val userId: String,
        val username: String,
        val packageName: String,
        val callingAppInfo: String?,
        val clientDataHash: ByteArray?,
    )
}
