package com.michaeltchuang.walletsdk.ui.passkeys

import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.provider.PendingIntentHandler
import androidx.credentials.provider.ProviderCreateCredentialRequest
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetLocalAccount
import com.michaeltchuang.walletsdk.core.passkeys.domain.model.PublicKeyCredentialCreationOptions
import com.michaeltchuang.walletsdk.core.passkeys.domain.usecase.DoesPasskeyExist
import com.michaeltchuang.walletsdk.core.passkeys.mapper.CreatePasskeyParamsMapper
import com.michaeltchuang.walletsdk.core.passkeys.model.CreatePasskeyIntentValidationResult
import com.michaeltchuang.walletsdk.core.passkeys.model.PasskeySigningProvider
import com.michaeltchuang.walletsdk.core.passkeys.validator.AppInfoValidationResult.AppInfoNotFound
import com.michaeltchuang.walletsdk.core.passkeys.validator.AppInfoValidationResult.FailedToValidateOrigin
import com.michaeltchuang.walletsdk.core.passkeys.validator.AppInfoValidationResult.FailedToValidateRP
import com.michaeltchuang.walletsdk.core.passkeys.validator.AppInfoValidationResult.Success
import com.michaeltchuang.walletsdk.core.passkeys.validator.CallingAppInfoValidator
import com.michaeltchuang.walletsdk.core.passkeys.validator.CreatePasskeyIntentValidator

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
internal class DefaultCreatePasskeyIntentValidator(
    private val appInfoValidator: CallingAppInfoValidator,
    private val createPasskeyParamsMapper: CreatePasskeyParamsMapper,
    private val doesPasskeyExist: DoesPasskeyExist,
    private val getLocalAccount: GetLocalAccount,
) : CreatePasskeyIntentValidator {
    override suspend fun validate(intent: Intent): CreatePasskeyIntentValidationResult {
        val createPasskeyRequest = PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent)
        val requestExtras = intent.getBundleExtra(PasskeyProviderService.EXTRA_INTENT_DATA_KEY)
        val accountAddress = requestExtras?.getString(PasskeyProviderService.ADDRESS_KEY)
        val signingProviderName = requestExtras?.getString(PasskeyProviderService.SIGNING_PROVIDER_KEY)
        var signingProvider = signingProviderName?.let { runCatching { PasskeySigningProvider.valueOf(it) }.getOrNull() }
            ?: PasskeySigningProvider.BIP39_DETERMINISTIC
        if (createPasskeyRequest == null || accountAddress == null) {
            return CreatePasskeyIntentValidationResult.UnableToExtractData
        }
        if (getLocalAccount(accountAddress) is LocalAccount.SeedVault) {
            signingProvider = PasskeySigningProvider.SOLANA_SEED_VAULT
        }

        val biometricPromptResult = createPasskeyRequest.biometricPromptResult
        if (biometricPromptResult?.authenticationError != null) {
            val error = biometricPromptResult.authenticationError!!
            val message = error.errorMsg?.toString().orEmpty()
            return CreatePasskeyIntentValidationResult.BiometricError(error.errorCode, message)
        }

        return if (createPasskeyRequest.callingRequest is CreatePublicKeyCredentialRequest) {
            getIntentResultValidatingAppInfo(createPasskeyRequest, accountAddress, signingProvider)
        } else {
            CreatePasskeyIntentValidationResult.InvalidRequestType
        }
    }

    private suspend fun getIntentResultValidatingAppInfo(
        createPasskeyRequest: ProviderCreateCredentialRequest,
        algoAddress: String,
        signingProvider: PasskeySigningProvider,
    ): CreatePasskeyIntentValidationResult {
        val publicKeyRequest = createPasskeyRequest.callingRequest as CreatePublicKeyCredentialRequest
        val requestOptions = PublicKeyCredentialCreationOptions(publicKeyRequest.requestJson)

        if (doesPasskeyExist(requestOptions.rp.id, requestOptions.user.name, algoAddress)) {
            return CreatePasskeyIntentValidationResult.ExistingPasskey
        }

        val validationResult =
            appInfoValidator.validateCallingApp(
                requestOptions.rp.id,
                createPasskeyRequest.callingAppInfo,
            )
        return when (validationResult) {
            is AppInfoNotFound -> CreatePasskeyIntentValidationResult.AppInfoNotFound
            is FailedToValidateRP -> CreatePasskeyIntentValidationResult.FailedToValidateRP
            is FailedToValidateOrigin -> CreatePasskeyIntentValidationResult.FailedToValidateOrigin
            is Success -> {
                val appInfoOrigin = validationResult.callingAppInfoOrigin
                val params = createPasskeyParamsMapper(createPasskeyRequest, algoAddress, appInfoOrigin, signingProvider)
                CreatePasskeyIntentValidationResult.Success(createPasskeyRequest, params)
            }
        }
    }
}
