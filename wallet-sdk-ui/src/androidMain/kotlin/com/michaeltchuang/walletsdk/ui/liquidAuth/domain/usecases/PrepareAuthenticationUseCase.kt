package com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases

import com.google.android.gms.fido.fido2.api.common.PublicKeyCredentialRequestOptions
import com.michaeltchuang.walletsdk.core.liquidAuth.auth.Cookie
import com.michaeltchuang.walletsdk.core.liquidAuth.auth.fido2.toPublicKeyCredentialRequestOptions
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.usecases.AssertionApiUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.AnswerViewModel
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.AuthMessage
import io.github.aakira.napier.Napier
import okhttp3.Response
import okhttp3.ResponseBody

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
class PrepareAuthenticationUseCase(
    private val assertionApiUseCase: AssertionApiUseCase,
) {
    companion object {
        private const val TAG = "PrepareAuthenticationUseCase"
    }

    /**
     * Result of authentication preparation
     */
    sealed class Result {
        data class Success(
            val publicKeyCredentialRequestOptions: PublicKeyCredentialRequestOptions,
            val sessionId: String?,
        ) : Result()

        data class CredentialNotFound(
            val message: String,
        ) : Result()

        data class Error(
            val message: String,
            val statusCode: Int? = null,
        ) : Result()
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
        onCredentialNotFound: () -> Unit = {},
    ): Result {
        return try {
            Napier.d("========================================", tag = TAG)
            Napier.d("🔓 PREPARING AUTHENTICATION", tag = TAG)
            Napier.d("Origin: ${authMessage.origin}", tag = TAG)
            Napier.d("Credential ID: $credentialId", tag = TAG)
            Napier.d("========================================", tag = TAG)

            // Step 1: Fetch assertion options from server
            val response =
                assertionApiUseCase.postAssertionOptions(
                    authMessage.origin,
                    viewModel.userAgent,
                    credentialId,
                )

            Napier.d("Server response received", tag = TAG)
            Napier.d("HTTP Status: ${response.code} ${response.message}", tag = TAG)

            // Step 2: Extract and validate response
            val responseBodyString = response.body?.string()
            Napier.d("Response body length: ${responseBodyString?.length ?: 0} characters", tag = TAG)

            // Step 3: Check for credential not found
            if (!response.isSuccessful) {
                Napier.e("Server returned error response: ${response.code} ${response.message}", tag = TAG)

                // Special handling for credential not found
                if (response.code == 401 && responseBodyString?.contains("not_found") == true) {
                    Napier.w("⚠️ Credential not found on server", tag = TAG)
                    onCredentialNotFound()
                    return Result.CredentialNotFound(
                        "Credential not found on server. Please re-register.",
                    )
                }

                return Result.Error(
                    "Server error: ${response.code} ${response.message}",
                    response.code,
                )
            }

            // Step 4: Extract session
            val sessionId = extractSessionFromResponse(response)
            onSessionUpdate(sessionId)

            // Step 5: Convert to PublicKeyCredentialRequestOptions
            val publicKeyCredentialRequestOptions =
                try {
                    // Recreate response body since we consumed it
                    val recreatedBody =
                        responseBodyString?.let {
                            ResponseBody.Companion.create(
                                response.body?.contentType(),
                                it,
                            )
                        }

                    if (recreatedBody == null) {
                        throw IllegalArgumentException("Response body is null")
                    }

                    recreatedBody.toPublicKeyCredentialRequestOptions()
                } catch (e: Exception) {
                    Napier.e("Failed to parse PublicKeyCredentialRequestOptions", e, tag = TAG)
                    return Result.Error(
                        "Failed to parse authentication options: ${e.message}",
                    )
                }

            Napier.d("✅ Authentication preparation successful", tag = TAG)
            Napier.d("========================================", tag = TAG)

            Result.Success(
                publicKeyCredentialRequestOptions = publicKeyCredentialRequestOptions,
                sessionId = sessionId,
            )
        } catch (e: Exception) {
            Napier.e("Error during authentication preparation", e, tag = TAG)
            Result.Error("Authentication preparation failed: ${e.message}")
        }
    }

    /**
     * Extract session ID from HTTP response
     */
    private fun extractSessionFromResponse(response: Response): String? =
        try {
            val cookie = Cookie.fromResponse(response)
            cookie?.let { Cookie.getID(it) }
        } catch (e: Exception) {
            Napier.w("Failed to extract session from response", e, tag = TAG)
            null
        }
}
