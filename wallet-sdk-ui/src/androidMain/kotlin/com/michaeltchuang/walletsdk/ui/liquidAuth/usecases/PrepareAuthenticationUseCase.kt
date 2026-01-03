package com.michaeltchuang.walletsdk.ui.liquidAuth.usecases

import android.util.Log
import com.michaeltchuang.walletsdk.core.liquidAuth.auth.connect.AuthMessage
import com.michaeltchuang.walletsdk.core.liquidAuth.auth.fido2.toPublicKeyCredentialRequestOptions
import com.michaeltchuang.walletsdk.ui.liquidAuth.AnswerViewModel
import com.google.android.gms.fido.fido2.api.common.PublicKeyCredentialRequestOptions

/**
 * Use case for preparing FIDO2 authentication
 *
 * Handles the flow of:
 * 1. Fetching assertion options from server
 * 2. Handling credential not found scenarios
 * 3. Converting to PublicKeyCredentialRequestOptions
 * 4. Extracting session information
 *
 * This separates authentication preparation logic from the Activity
 */
class PrepareAuthenticationUseCase {
    companion object {
        private const val TAG = "PrepareAuthenticationUseCase"
    }

    /**
     * Result of authentication preparation
     */
    sealed class Result {
        data class Success(
            val publicKeyCredentialRequestOptions: PublicKeyCredentialRequestOptions,
            val sessionId: String?
        ) : Result()

        data class CredentialNotFound(val message: String) : Result()
        data class Error(val message: String, val statusCode: Int? = null) : Result()
    }

    /**
     * Prepare authentication by fetching assertion options
     *
     * @param authMessage The authentication message containing origin
     * @param credentialId The credential ID to authenticate with
     * @param viewModel The ViewModel for API calls
     * @param onSessionUpdate Callback when session is extracted
     * @param onCredentialNotFound Callback when credential is not found on server
     * @return Result indicating success or error
     */
    suspend operator fun invoke(
        authMessage: AuthMessage,
        credentialId: String,
        viewModel: AnswerViewModel,
        onSessionUpdate: (String?) -> Unit = {},
        onCredentialNotFound: () -> Unit = {}
    ): Result {
        return try {
            Log.d(TAG, "========================================")
            Log.d(TAG, "🔓 PREPARING AUTHENTICATION")
            Log.d(TAG, "Origin: ${authMessage.origin}")
            Log.d(TAG, "Credential ID: $credentialId")
            Log.d(TAG, "========================================")

            // Step 1: Fetch assertion options from server
            val response = viewModel.fetchAssertionOptions(
                authMessage.origin,
                viewModel.userAgent,
                credentialId
            )

            Log.d(TAG, "Server response received")
            Log.d(TAG, "HTTP Status: ${response.code} ${response.message}")

            // Step 2: Extract and validate response
            val responseBodyString = response.body?.string()
            Log.d(TAG, "Response body length: ${responseBodyString?.length ?: 0} characters")

            // Step 3: Check for credential not found
            if (!response.isSuccessful) {
                Log.e(TAG, "Server returned error response: ${response.code} ${response.message}")

                // Special handling for credential not found
                if (response.code == 401 && responseBodyString?.contains("not_found") == true) {
                    Log.w(TAG, "⚠️ Credential not found on server")
                    onCredentialNotFound()
                    return Result.CredentialNotFound(
                        "Credential not found on server. Please re-register."
                    )
                }

                return Result.Error(
                    "Server error: ${response.code} ${response.message}",
                    response.code
                )
            }

            // Step 4: Extract session
            val sessionId = extractSessionFromResponse(response)
            onSessionUpdate(sessionId)

            // Step 5: Convert to PublicKeyCredentialRequestOptions
            val publicKeyCredentialRequestOptions = try {
                // Recreate response body since we consumed it
                val recreatedBody = responseBodyString?.let {
                    okhttp3.ResponseBody.create(
                        response.body?.contentType(),
                        it
                    )
                }

                if (recreatedBody == null) {
                    throw IllegalArgumentException("Response body is null")
                }

                recreatedBody.toPublicKeyCredentialRequestOptions()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse PublicKeyCredentialRequestOptions", e)
                return Result.Error(
                    "Failed to parse authentication options: ${e.message}"
                )
            }

            Log.d(TAG, "✅ Authentication preparation successful")
            Log.d(TAG, "========================================")

            Result.Success(
                publicKeyCredentialRequestOptions = publicKeyCredentialRequestOptions,
                sessionId = sessionId
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error during authentication preparation", e)
            Result.Error("Authentication preparation failed: ${e.message}")
        }
    }

    /**
     * Extract session ID from HTTP response
     */
    private fun extractSessionFromResponse(response: okhttp3.Response): String? {
        return try {
            val cookie = com.michaeltchuang.walletsdk.core.liquidAuth.auth.Cookie.fromResponse(response)
            cookie?.let { com.michaeltchuang.walletsdk.core.liquidAuth.auth.Cookie.getID(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract session from response", e)
            null
        }
    }
}
