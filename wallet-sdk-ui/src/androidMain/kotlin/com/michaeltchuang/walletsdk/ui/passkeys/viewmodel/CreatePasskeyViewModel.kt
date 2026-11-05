package com.michaeltchuang.walletsdk.ui.passkeys.viewmodel

import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.passkeys.CreatePublicKeyCredentialResponseProcessor
import com.michaeltchuang.walletsdk.core.passkeys.domain.usecase.AddNewPasskey
import com.michaeltchuang.walletsdk.core.passkeys.mapper.CreatePublicKeyCredentialResponseArgsMapper
import com.michaeltchuang.walletsdk.core.passkeys.model.CreatePasskeyIntentValidationResult
import com.michaeltchuang.walletsdk.core.passkeys.model.CreatePasskeyParams
import com.michaeltchuang.walletsdk.core.passkeys.validator.CreatePasskeyIntentValidator
import io.github.aakira.napier.Napier
import kotlinx.coroutines.launch

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class CreatePasskeyViewModel(
    private val eventDelegate: EventDelegate<ViewEvent>,
    private val addNewPasskey: AddNewPasskey,
    private val createPublicKeyCredentialResponseProcessor: CreatePublicKeyCredentialResponseProcessor,
    private val createPublicKeyCredentialResponseArgsMapper: CreatePublicKeyCredentialResponseArgsMapper,
    private val createPasskeyIntentValidator: CreatePasskeyIntentValidator,
) : ViewModel(),
    EventViewModel<CreatePasskeyViewModel.ViewEvent> by eventDelegate {
    fun processIntent(intent: Intent) {
        viewModelScope.launch {
            val validation = createPasskeyIntentValidator.validate(intent)
            Napier.d("Create passkey validation: ${validation::class.simpleName}", tag = TAG)
            when (val result = validation) {
                CreatePasskeyIntentValidationResult.AppInfoNotFound -> finishWithError("Calling app info not found")
                CreatePasskeyIntentValidationResult.FailedToValidateOrigin -> finishWithError("Failed to validate origin")
                CreatePasskeyIntentValidationResult.FailedToValidateRP -> finishWithError("Failed to validate relying party")
                CreatePasskeyIntentValidationResult.InvalidRequestType -> finishWithError("Unexpected create request found")
                CreatePasskeyIntentValidationResult.UnableToExtractData -> finishWithError("Unable to extract data")
                CreatePasskeyIntentValidationResult.ExistingPasskey -> finishWithError("This passkey already exists")
                is CreatePasskeyIntentValidationResult.BiometricError -> finishWithBiometricError(result)
                is CreatePasskeyIntentValidationResult.Success -> {
                    if (result.request.biometricPromptResult?.isSuccessful == true) {
                        createPasskey(result.params)
                    } else {
                        eventDelegate.sendEvent(
                            ViewEvent.AuthenticateCreatePasskeyWithBiometrics(
                                result.params,
                            ),
                        )
                    }
                }
            }
        }
    }

    private suspend fun finishWithError(errorMessage: String) {
        Napier.e("Create passkey failed: $errorMessage", tag = TAG)
        eventDelegate.sendEvent(ViewEvent.FinishActivityWithCreateError(errorMessage))
    }

    private suspend fun finishWithBiometricError(result: CreatePasskeyIntentValidationResult.BiometricError) {
        eventDelegate.sendEvent(ViewEvent.FinishActivityWithCreateBiometricError(result.code, result.message))
    }

    fun createPasskey(params: CreatePasskeyParams) {
        viewModelScope.launch {
            with(params) {
                try {
                    val args = createPublicKeyCredentialResponseArgsMapper(params, appInfoOrigin)
                    val responseData = createPublicKeyCredentialResponseProcessor(args)
                    addNewPasskey(address, requestOptions, responseData.credentialId)
                    Napier.d("Passkey saved for $address using $signingProvider", tag = TAG)
                    eventDelegate.sendEvent(ViewEvent.SetCreateResponseAndFinishActivity(responseData.response))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Napier.e("Creating the passkey for $address failed", e, tag = TAG)
                    finishWithError("Failed to create passkey: ${e.message}")
                }
            }
        }
    }

    private companion object {
        const val TAG = "CreatePasskeyViewModel"
    }

    sealed interface ViewEvent {
        data class FinishActivityWithCreateError(
            val errorMessage: String,
        ) : ViewEvent

        data class FinishActivityWithCreateBiometricError(
            val errorCode: Int,
            val errorMessage: String,
        ) : ViewEvent

        data class AuthenticateCreatePasskeyWithBiometrics(
            val params: CreatePasskeyParams,
        ) : ViewEvent

        data class SetCreateResponseAndFinishActivity(
            val response: CreatePublicKeyCredentialResponse,
        ) : ViewEvent
    }
}
