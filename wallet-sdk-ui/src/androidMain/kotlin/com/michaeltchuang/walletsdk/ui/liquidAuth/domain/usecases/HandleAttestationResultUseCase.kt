package com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases

import android.app.Activity
import android.os.Build
import androidx.activity.result.ActivityResult
import com.google.android.gms.fido.Fido
import com.google.android.gms.fido.fido2.api.common.AuthenticatorErrorResponse
import com.google.android.gms.fido.fido2.api.common.ErrorCode
import com.google.android.gms.fido.fido2.api.common.PublicKeyCredential
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.usecases.AttestationApiUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.AnswerViewModel
import io.github.aakira.napier.Napier
import io.ktor.http.origin
import org.json.JSONObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Use case for handling FIDO2 attestation result
 *
 * Processes the ActivityResult from the FIDO2 registration intent and handles:
 * - Result code validation
 * - Error parsing and handling
 * - Credential extraction
 * - Liquid extension JSON creation
 * - Server submission
 *
 * This separates complex attestation result handling from the Activity
 */
class HandleAttestationResultUseCase(
    private val attestationApiUseCase: AttestationApiUseCase,
) {
    companion object {
        private const val TAG = "HandleAttestationResultUseCase"
    }

    /**
     * Result of attestation handling
     */
    sealed class Result {
        data class Success(
            val credential: PublicKeyCredential,
            val responseBody: String,
        ) : Result()

        data class Cancelled(
            val message: String,
        ) : Result()

        data class Error(
            val message: String,
            val isFido2Error: Boolean = false,
        ) : Result()
    }

    /**
     * Handle the attestation activity result
     *
     * @param activityResult The result from the FIDO2 registration intent
     * @param algoAddress The Algorand address being registered
     * @param currentChallenge The signed challenge
     * @param requestId The authentication request ID
     * @param origin The origin URL
     * @param viewModel The ViewModel for API calls
     * @return Result indicating success, cancellation, or error
     */
    @OptIn(ExperimentalEncodingApi::class)
    suspend operator fun invoke(
        activityResult: ActivityResult,
        viewModel: AnswerViewModel,
    ): Result {
        try {
            Napier.d("========================================", tag = TAG)
            Napier.d("📱 PROCESSING ATTESTATION RESULT", tag = TAG)
            Napier.d("Result code: ${activityResult.resultCode}", tag = TAG)
            Napier.d("Expected RESULT_OK: ${Activity.RESULT_OK}", tag = TAG)
            Napier.d("========================================", tag = TAG)

            // Step 1: Validate result code
            if (activityResult.resultCode != Activity.RESULT_OK) {
                return handleCancelledOrFailed(activityResult)
            }

            // Step 2: Extract credential bytes
            val bytes = activityResult.data?.getByteArrayExtra(Fido.FIDO2_KEY_CREDENTIAL_EXTRA)
            if (bytes == null) {
                Napier.e("Credential bytes are null", tag = TAG)
                return Result.Error("No credential data received")
            }

            Napier.d("✅ Credential bytes received, size: ${bytes.size}", tag = TAG)

            // Step 3: Deserialize credential
            val credential = PublicKeyCredential.deserializeFromBytes(bytes)
            Napier.d("Credential ID: ${credential.id}", tag = TAG)
            Napier.d("Credential Type: ${credential.type}", tag = TAG)

            // Step 4: Check for authenticator errors
            val response = credential.response
            if (response is AuthenticatorErrorResponse) {
                return handleAuthenticatorError(response)
            }

            // Step 5: Validate challenge
            if (viewModel.currentChallenge == null) {
                Napier.e("Challenge signature is null", tag = TAG)
                return Result.Error("Challenge signature is missing")
            }

            // Step 6: Build liquid extension JSON
            val liquidExtJSON =
                buildLiquidExtensionJson(
                    algoAddress = viewModel.accountAddress.value,
                    requestId = viewModel.authMessage.value!!.requestId,
                    currentChallenge = viewModel.currentChallenge!!,
                    viewModel = viewModel,
                )

            Napier.d("========================================", tag = TAG)
            Napier.d("📤 SUBMITTING CREDENTIAL TO SERVER", tag = TAG)
            Napier.d("URL: ${viewModel.authMessage.value!!.origin}/attestation/response", tag = TAG)
            Napier.d("Credential ID: ${credential.id}", tag = TAG)
            Napier.d("========================================", tag = TAG)

            // Step 7: Submit to server
            val attestationResponse =
                attestationApiUseCase.postAttestationResult(
                    viewModel.authMessage.value!!.origin,
                    viewModel.userAgent,
                    credential,
                    liquidExtJSON,
                )

            val responseBody = attestationResponse.peekBody(Long.MAX_VALUE).string()

            Napier.d("========================================", tag = TAG)
            Napier.d("📡 ATTESTATION RESPONSE RECEIVED", tag = TAG)
            Napier.d("HTTP Status: ${attestationResponse.code} ${attestationResponse.message}", tag = TAG)
            Napier.d("========================================", tag = TAG)

            // Step 8: Validate server response
            if (!attestationResponse.isSuccessful) {
                Napier.e("❌ REGISTRATION FAILED!", tag = TAG)
                Napier.e("Server rejected the credential", tag = TAG)
                Napier.e("Status: ${attestationResponse.code}", tag = TAG)
                Napier.e("Response: $responseBody", tag = TAG)
                return Result.Error(
                    "Registration failed: ${attestationResponse.code} - Check server logs",
                )
            }

            Napier.d("✅ FIDO2 REGISTRATION SUCCESSFUL!", tag = TAG)
            Napier.d("========================================", tag = TAG)

            viewModel.saveCredential(
                account = viewModel.accountAddress.value,
                credential = credential,
                response = viewModel.getAttestationApiResponse()!!,
            )

            return Result.Success(credential, responseBody)
        } catch (e: Exception) {
            Napier.e("❌ Exception in handleAttestationResult", e, tag = TAG)
            Napier.e("Exception type: ${e.javaClass.name}", tag = TAG)
            Napier.e("Exception message: ${e.message}", tag = TAG)
            e.printStackTrace()
            return Result.Error("Error processing attestation: ${e.message}")
        }
    }

    /**
     * Handle cancelled or failed attestation
     */
    private fun handleCancelledOrFailed(activityResult: ActivityResult): Result {
        Napier.e("Attestation cancelled or failed. Result code: ${activityResult.resultCode}", tag = TAG)

        // Try to extract error details
        activityResult.data?.let { data ->
            val errorBytes = data.getByteArrayExtra(Fido.FIDO2_KEY_ERROR_EXTRA)
            if (errorBytes != null) {
                try {
                    val errorResponse = PublicKeyCredential.deserializeFromBytes(errorBytes)
                    if (errorResponse.response is AuthenticatorErrorResponse) {
                        val error = errorResponse.response as AuthenticatorErrorResponse
                        Napier.e("FIDO2 Error Code: ${error.errorCode}", tag = TAG)
                        Napier.e("FIDO2 Error Message: ${error.errorMessage}", tag = TAG)
                        return Result.Error(
                            "FIDO2 Error: ${error.errorMessage}",
                            isFido2Error = true,
                        )
                    }
                } catch (e: Exception) {
                    Napier.e("Failed to parse error response", e, tag = TAG)
                }
            }
        }

        return Result.Cancelled("Attestation was cancelled by user")
    }

    /**
     * Handle authenticator error response
     */
    private fun handleAuthenticatorError(response: AuthenticatorErrorResponse): Result {
        Napier.e("❌ FIDO2 AUTHENTICATOR ERROR", tag = TAG)
        Napier.e("Error Code: ${response.errorCode}", tag = TAG)
        Napier.e("Error Code Name: ${response.errorCode.name}", tag = TAG)
        Napier.e("Error Message: ${response.errorMessage}", tag = TAG)

        val message =
            if (response.errorCode === ErrorCode.UNKNOWN_ERR) {
                "Something Went Wrong: ${response.errorMessage}"
            } else {
                "FIDO2 Error: ${response.errorMessage}"
            }

        return Result.Error(message, isFido2Error = true)
    }

    /**
     * Build the liquid extension JSON for FIDO2 request
     */
    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun buildLiquidExtensionJson(
        algoAddress: String,
        requestId: String,
        currentChallenge: ByteArray,
        viewModel: AnswerViewModel,
    ): JSONObject {
        val accountType = viewModel.getAccountTypeForFido2(algoAddress)
        val publicKey = viewModel.getAccountPublicKey(algoAddress)

        return JSONObject().apply {
            put("type", accountType)
            put("requestId", requestId)
            put("address", algoAddress)
            put("publicKey", Base64.encode(publicKey))
            put("signature", Base64.encode(currentChallenge))
            put("device", Build.MODEL)
        }
    }
}
