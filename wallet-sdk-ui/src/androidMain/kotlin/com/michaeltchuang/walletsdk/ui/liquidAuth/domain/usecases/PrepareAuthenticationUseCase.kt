package com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases

import com.michaeltchuang.walletsdk.core.liquidAuth.auth.Cookie
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.usecases.AssertionApiUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.AnswerViewModel
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.AuthMessage
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

/**
 * Use case for preparing FIDO2 authentication
 *
 * Handles the flow of:
 * 1. Fetching assertion options from server
 * 2. Handling credential not found scenarios
 * 3. Preserving server JSON while constraining authentication to the requested credential
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
            val requestJson: String,
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

            require(credentialId.isNotBlank()) { "Credential ID is required" }

            assertionApiUseCase
                .postAssertionOptions(
                    authMessage.origin,
                    viewModel.userAgent,
                    credentialId,
                ).use { response ->
                    val responseBodyString = response.body.string()
                    if (!response.isSuccessful) {
                        // Preserve recovery when the server no longer knows the local credential.
                        if (response.code == 401 && responseBodyString.contains("not_found")) {
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

                    val sessionId = extractSessionFromResponse(response)
                    onSessionUpdate(sessionId)

                    val requestOptions = JSONObject(responseBodyString)
                    val constrainedCredentials = JSONArray()
                    if (requestOptions.has("allowCredentials") && requestOptions.getJSONArray("allowCredentials").length() > 0) {
                        val allowedCredentials = requestOptions.getJSONArray("allowCredentials")
                        for (index in 0 until allowedCredentials.length()) {
                            val descriptor = allowedCredentials.getJSONObject(index)
                            // Base64url padding does not change the credential identity.
                            if (descriptor.getString("id").trimEnd('=') == credentialId.trimEnd('=')) {
                                constrainedCredentials.put(descriptor)
                            }
                        }
                        if (constrainedCredentials.length() == 0) {
                            return Result.Error("Server authentication options exclude the requested credential")
                        }
                    } else {
                        constrainedCredentials.put(
                            JSONObject().apply {
                                put("type", "public-key")
                                put("id", credentialId)
                            },
                        )
                    }
                    requestOptions.put("allowCredentials", constrainedCredentials)

                    Result.Success(
                        requestJson = requestOptions.toString(),
                        sessionId = sessionId,
                    )
                }
        } catch (e: CancellationException) {
            throw e
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Napier.w("Failed to extract session from response", e, tag = TAG)
            null
        }
}
