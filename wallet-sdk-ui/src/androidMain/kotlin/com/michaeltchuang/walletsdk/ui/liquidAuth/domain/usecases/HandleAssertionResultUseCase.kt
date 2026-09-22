package com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases

import android.app.Activity
import androidx.activity.result.ActivityResult
import com.google.android.gms.fido.Fido
import com.google.android.gms.fido.fido2.api.common.AuthenticatorErrorResponse
import com.google.android.gms.fido.fido2.api.common.PublicKeyCredential
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.usecases.AssertionApiUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.AnswerViewModel
import io.github.aakira.napier.Napier
import org.json.JSONArray
import org.json.JSONObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Use case for handling FIDO2 assertion (authentication) result
 *
 * Processes the ActivityResult from the FIDO2 authentication intent and handles:
 * - Result code validation
 * - Credential extraction
 * - Error handling
 * - Liquid extension JSON creation
 * - Server submission
 * - Counter update
 *
 * This separates assertion result handling from the Activity
 */
class HandleAssertionResultUseCase(
    private val assertionApiUseCase: AssertionApiUseCase,
) {
    companion object {
        private const val TAG = "HandleAssertionResultUseCase"
    }

    /**
     * Result of assertion handling
     */
    sealed class Result {
        data class Success(
            val credential: PublicKeyCredential,
            val responseBody: String,
            val prevCounter: Int,
        ) : Result()

        data class Cancelled(
            val message: String,
        ) : Result()

        data class Error(
            val message: String,
        ) : Result()
    }

    /**
     * Handle the assertion activity result
     *
     * @param activityResult The result from the FIDO2 authentication intent
     * @param viewModel The ViewModel for API calls
     * @return Result indicating success, cancellation, or error
     */
    suspend operator fun invoke(
        activityResult: ActivityResult,
        viewModel: AnswerViewModel,
    ): Result {
        try {
            Napier.d("========================================", tag = TAG)
            Napier.d("📱 PROCESSING ASSERTION RESULT", tag = TAG)
            Napier.d("Result code: ${activityResult.resultCode}", tag = TAG)
            Napier.d("========================================", tag = TAG)

            // Step 1: Validate result code
            if (activityResult.resultCode != Activity.RESULT_OK) {
                Napier.w("Assertion cancelled or failed", tag = TAG)
                return Result.Cancelled("Authentication was cancelled")
            }

            // Step 2: Extract credential bytes
            val bytes = activityResult.data?.getByteArrayExtra(Fido.FIDO2_KEY_CREDENTIAL_EXTRA)
            if (bytes == null) {
                Napier.e("Credential bytes are null", tag = TAG)
                return Result.Error("No credential data received")
            }

            // Step 3: Deserialize credential
            val credential = PublicKeyCredential.deserializeFromBytes(bytes)
            Napier.d("✅ Authentication credential received", tag = TAG)
            Napier.d("Credential ID: ${credential.id}", tag = TAG)

            // Step 4: Check for authenticator errors
            val response = credential.response
            if (response is AuthenticatorErrorResponse) {
                Napier.e("Authenticator error: ${response.errorMessage}", tag = TAG)
                return Result.Error(response.errorMessage ?: "Authentication error")
            }

            // Step 5: Build liquid extension JSON
            val liquidExtJSON =
                buildLiquidExtensionJson(
                    accountType = viewModel.getAccountTypeForFido2(viewModel.accountAddress.value),
                    requestId = viewModel.authMessage.value!!.requestId,
                    accountAddress = viewModel.accountAddress.value,
                    publicKey = viewModel.getAccountPublicKey(viewModel.accountAddress.value),
                    challengeSignature = viewModel.currentChallenge,
                )

            Napier.d("Posting authentication assertion to server...", tag = TAG)

            // Step 6: Submit to server
            val serverResponse =
                assertionApiUseCase.postAssertionResult(
                    viewModel.authMessage.value!!.origin,
                    viewModel.userAgent,
                    credential,
                    liquidExtJSON,
                )

            Napier.d("========================================", tag = TAG)
            Napier.d("✅ AUTHENTICATION SUCCESSFUL!", tag = TAG)
            Napier.d("Server response: ${serverResponse.code}", tag = TAG)
            Napier.d("Credential was recognized and validated!", tag = TAG)
            Napier.d("========================================", tag = TAG)

            // Step 7: Parse response and extract counter
            val responseBody = serverResponse.body!!.string()
            val prevCounter = extractPrevCounter(responseBody, credential.id)

            return Result.Success(
                credential = credential,
                responseBody = responseBody,
                prevCounter = prevCounter,
            )
        } catch (e: Exception) {
            Napier.e("❌ Exception in handleAssertionResult", e, tag = TAG)
            Napier.e("Exception type: ${e.javaClass.name}", tag = TAG)
            Napier.e("Exception message: ${e.message}", tag = TAG)
            e.printStackTrace()
            return Result.Error("Error processing authentication: ${e.message}")
        }
    }

    /**
     * Build the liquid extension JSON for FIDO2 request
     */
    @OptIn(ExperimentalEncodingApi::class)
    private fun buildLiquidExtensionJson(
        accountType: String,
        requestId: String,
        accountAddress: String,
        publicKey: ByteArray,
        challengeSignature: ByteArray?,
    ): JSONObject =
        JSONObject().apply {
            put("type", accountType)
            put("requestId", requestId)
            put("address", accountAddress)
            put("publicKey", Base64.encode(publicKey))
            if (challengeSignature != null) {
                put("signature", Base64.encode(challengeSignature))
            }
        }

    /**
     * Extract previous counter from server response
     */
    private fun extractPrevCounter(
        responseBody: String,
        credentialId: String?,
    ): Int {
        return try {
            val json = JSONObject(responseBody)
            val creds = json.get("credentials") as? JSONArray

            if (creds != null && creds.length() > 0) {
                for (i in 0 until creds.length()) {
                    val cred: JSONObject = creds.getJSONObject(i)
                    if (cred.get("credId") == credentialId) {
                        return cred.get("prevCounter") as? Int ?: 0
                    }
                }
            }
            0
        } catch (e: Exception) {
            Napier.w("Failed to extract prevCounter from response", e, tag = TAG)
            0
        }
    }
}
