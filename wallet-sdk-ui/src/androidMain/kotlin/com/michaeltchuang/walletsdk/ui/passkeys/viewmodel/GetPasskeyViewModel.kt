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

package com.michaeltchuang.walletsdk.ui.passkeys.viewmodel

import android.content.Intent
import androidx.credentials.GetCredentialResponse
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.passkeys.GetCredentialResponseProcessor
import com.michaeltchuang.walletsdk.core.passkeys.model.GetCredentialsParams
import com.michaeltchuang.walletsdk.core.passkeys.model.GetPasskeyIntentValidationResult
import com.michaeltchuang.walletsdk.core.passkeys.validator.GetPasskeyIntentValidator
import com.michaeltchuang.walletsdk.ui.passkeys.viewmodel.GetPasskeyViewModel.ViewEvent
import kotlinx.coroutines.launch

class GetPasskeyViewModel(
    private val getPasskeyIntentValidator: GetPasskeyIntentValidator,
    private val getCredentialResponseProcessor: GetCredentialResponseProcessor,
    private val eventDelegate: EventDelegate<ViewEvent>
) : ViewModel(), EventViewModel<ViewEvent> by eventDelegate {

    fun processIntent(intent: Intent) {
        viewModelScope.launch {
            when (val result = getPasskeyIntentValidator.validate(intent)) {
                GetPasskeyIntentValidationResult.AppInfoNotFound -> finishWithError("Calling app info not found")
                GetPasskeyIntentValidationResult.FailedToValidateOrigin -> finishWithError("Failed to validate origin")
                GetPasskeyIntentValidationResult.FailedToValidateRP -> finishWithError("Failed to validate relying party")
                GetPasskeyIntentValidationResult.InvalidRequestType -> finishWithError("Unexpected create request found")
                GetPasskeyIntentValidationResult.UnableToExtractData -> finishWithError("Unable to extract data")
                GetPasskeyIntentValidationResult.PasskeyNotFound -> finishWithError("Requested credential not found")
                is GetPasskeyIntentValidationResult.Success -> {
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
        data class AuthenticateGetPasskeyWithBiometrics(val params: GetCredentialsParams) :
            ViewEvent
        data class SetGetResponseAndFinishActivity(val response: GetCredentialResponse) : ViewEvent
    }
}
