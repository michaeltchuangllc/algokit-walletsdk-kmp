package com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases

import android.app.Activity
import android.util.Log
import androidx.activity.result.ActivityResult
import com.google.android.gms.fido.Fido
import com.google.android.gms.fido.fido2.api.common.AuthenticatorErrorResponse
import com.google.android.gms.fido.fido2.api.common.PublicKeyCredential
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.usecases.AssertionApiUseCase
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.usecases.AttestationApiUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.AnswerViewModel
import io.ktor.http.origin
import org.json.JSONArray
import org.json.JSONObject

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
class HandleAssertionResultUseCase( private val assertionApiUseCase: AssertionApiUseCase,) {
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
            val prevCounter: Int
        ) : Result()

        data class Cancelled(val message: String) : Result()
        data class Error(val message: String) : Result()
    }

    /**
     * Handle the assertion activity result
     *
     * @param activityResult The result from the FIDO2 authentication intent
     * @param requestId The authentication request ID
     * @param origin The origin URL
     * @param algoAddress The Algorand address
     * @param viewModel The ViewModel for API calls
     * @return Result indicating success, cancellation, or error
     */
    suspend operator fun invoke(
        activityResult: ActivityResult,
        viewModel: AnswerViewModel
    ): Result {
        try {
            Log.d(TAG, "========================================")
            Log.d(TAG, "📱 PROCESSING ASSERTION RESULT")
            Log.d(TAG, "Result code: ${activityResult.resultCode}")
            Log.d(TAG, "========================================")

            // Step 1: Validate result code
            if (activityResult.resultCode != Activity.RESULT_OK) {
                Log.w(TAG, "Assertion cancelled or failed")
                return Result.Cancelled("Authentication was cancelled")
            }

            // Step 2: Extract credential bytes
            val bytes = activityResult.data?.getByteArrayExtra(Fido.FIDO2_KEY_CREDENTIAL_EXTRA)
            if (bytes == null) {
                Log.e(TAG, "Credential bytes are null")
                return Result.Error("No credential data received")
            }

            // Step 3: Deserialize credential
            val credential = PublicKeyCredential.deserializeFromBytes(bytes)
            Log.d(TAG, "✅ Authentication credential received")
            Log.d(TAG, "Credential ID: ${credential.id}")

            // Step 4: Check for authenticator errors
            val response = credential.response
            if (response is AuthenticatorErrorResponse) {
                Log.e(TAG, "Authenticator error: ${response.errorMessage}")
                return Result.Error(response.errorMessage ?: "Authentication error")
            }

            // Step 5: Build liquid extension JSON
            val liquidExtJSON = buildLiquidExtensionJson(
                accountType = viewModel.getAccountTypeForFido2(viewModel.accountAddress.value),
                requestId = viewModel.authMessage.value!!.requestId
            )

            Log.d(TAG, "Posting authentication assertion to server...")

            // Step 6: Submit to server
            val serverResponse = assertionApiUseCase.postAssertionResult(
                viewModel.authMessage.value!!.origin,
                viewModel.userAgent,
                credential,
                liquidExtJSON,
            )

            Log.d(TAG, "========================================")
            Log.d(TAG, "✅ AUTHENTICATION SUCCESSFUL!")
            Log.d(TAG, "Server response: ${serverResponse.code}")
            Log.d(TAG, "Credential was recognized and validated!")
            Log.d(TAG, "========================================")

            // Step 7: Parse response and extract counter
            val responseBody = serverResponse.body!!.string()
            val prevCounter = extractPrevCounter(responseBody, credential.id)

            return Result.Success(
                credential = credential,
                responseBody = responseBody,
                prevCounter = prevCounter
            )

        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception in handleAssertionResult", e)
            Log.e(TAG, "Exception type: ${e.javaClass.name}")
            Log.e(TAG, "Exception message: ${e.message}")
            e.printStackTrace()
            return Result.Error("Error processing authentication: ${e.message}")
        }
    }

    /**
     * Build the liquid extension JSON for FIDO2 request
     */
    private fun buildLiquidExtensionJson(
        accountType: String,
        requestId: String
    ): JSONObject {
        return JSONObject().apply {
            put("type", accountType)
            put("requestId", requestId)
        }
    }

    /**
     * Extract previous counter from server response
     */
    private fun extractPrevCounter(responseBody: String, credentialId: String?): Int {
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
            Log.w(TAG, "Failed to extract prevCounter from response", e)
            0
        }
    }
}
