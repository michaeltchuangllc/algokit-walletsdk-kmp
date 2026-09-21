package com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases

import android.net.Uri
import com.google.android.gms.fido.fido2.api.common.PublicKeyCredentialCreationOptions
import com.michaeltchuang.walletsdk.core.liquidAuth.auth.Cookie
import com.michaeltchuang.walletsdk.core.liquidAuth.auth.fido2.toPublicKeyCredentialCreationOptions
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.usecases.AttestationApiUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.AnswerViewModel
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.AuthMessage
import io.github.aakira.napier.Napier
import okhttp3.Response
import org.json.JSONObject

/**
 * Use case for registering a new passkey/credential
 *
 * Handles the complete flow of:
 * 1. Extracting RP ID from origin
 * 2. Building attestation options
 * 3. Fetching attestation options from FIDO2 server
 * 4. Converting to PublicKeyCredentialCreationOptions
 *
 * This separates the complex registration logic from the Activity
 */
class RegisterPasskeyUseCase(
    private val attestationApiUseCase: AttestationApiUseCase,
) {
    companion object {
        private const val TAG = "RegisterPasskeyUseCase"
    }

    /**
     * Result of the registration preparation
     */
    sealed class Result {
        data class Success(
            val pubKeyCredentialCreationOptions: PublicKeyCredentialCreationOptions,
            val attestationApiResponse: String,
            val sessionId: String?,
        ) : Result()

        data class Error(
            val message: String,
            val exception: Exception? = null,
        ) : Result()
    }

    /**
     * Prepare registration by fetching attestation options
     *
     * @param authMessage The authentication message containing origin and request ID
     * @param algoAddress The Algorand address to register
     * @param viewModel The ViewModel for API calls
     * @param options Additional options for attestation
     * @param onSessionUpdate Callback when session is extracted from response
     * @return Result indicating success or error
     */
    suspend operator fun invoke(
        authMessage: AuthMessage,
        algoAddress: String,
        viewModel: AnswerViewModel,
        options: JSONObject = JSONObject(),
        onSessionUpdate: (String?) -> Unit = {},
    ): Result {
        return try {
            Napier.d("========================================", tag = TAG)
            Napier.d("🔐 STARTING REGISTRATION PREPARATION", tag = TAG)
            Napier.d("Account: $algoAddress", tag = TAG)
            Napier.d("Origin: ${authMessage.origin}", tag = TAG)
            Napier.d("RequestID: ${authMessage.requestId}", tag = TAG)
            Napier.d("========================================", tag = TAG)

            // Step 1: Extract RP ID from origin
            val rpId =
                extractRpIdFromOrigin(authMessage.origin)
                    ?: return Result.Error("Failed to extract RP ID from origin")

            // Step 2: Build attestation options
            val attestationOptions = buildAttestationOptions(algoAddress, rpId, options)
            Napier.d("✅ Attestation options built", tag = TAG)

            // Step 3: Fetch attestation options from FIDO2 server
            Napier.d("========================================", tag = TAG)
            Napier.d("📡 FETCHING ATTESTATION OPTIONS", tag = TAG)
            Napier.d("URL: ${authMessage.origin}/attestation/request", tag = TAG)
            Napier.d("User-Agent: ${viewModel.userAgent}", tag = TAG)
            Napier.d("========================================", tag = TAG)

            val response =
                attestationApiUseCase.postAttestationOptions(
                    authMessage.origin,
                    viewModel.userAgent,
                    attestationOptions,
                )

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                Napier.e("Server error ${response.code}: ${response.message}", tag = TAG)
                return Result.Error("Server error ${response.code}: ${response.message}")
            }

            val attestationApiResponse = response.peekBody(Long.MAX_VALUE).string()
            Napier.d("✅ Attestation options received successfully", tag = TAG)

            // Step 4: Extract session cookie
            val sessionId = extractSessionFromResponse(response)
            onSessionUpdate(sessionId)

            // Step 5: Convert to PublicKeyCredentialCreationOptions
            val pubKeyCredentialCreationOptions =
                response.body!!.toPublicKeyCredentialCreationOptions(
                    overrideRpId = rpId,
                )

            Napier.d("✅ PublicKeyCredentialCreationOptions created", tag = TAG)
            Napier.d("RP ID: ${pubKeyCredentialCreationOptions.rp?.id}", tag = TAG)
            Napier.d("User: ${pubKeyCredentialCreationOptions.user?.name}", tag = TAG)
            Napier.d("Challenge length: ${pubKeyCredentialCreationOptions.challenge?.size}", tag = TAG)
            Napier.d("========================================", tag = TAG)

            Result.Success(
                pubKeyCredentialCreationOptions = pubKeyCredentialCreationOptions,
                attestationApiResponse = attestationApiResponse,
                sessionId = sessionId,
            )
        } catch (e: Exception) {
            Napier.e("Error during registration preparation", e, tag = TAG)
            Result.Error("Registration preparation failed: ${e.message}", e)
        }
    }

    /**
     * Extract RP ID (domain) from origin URL
     */
    private fun extractRpIdFromOrigin(origin: String): String? {
        return try {
            val parsedUri = Uri.parse(origin)
            val host = parsedUri.host

            if (host.isNullOrEmpty()) {
                Napier.e("Failed to extract host from origin: $origin", tag = TAG)
                return null
            }

            Napier.d("Extracted RP ID: $host from origin: $origin", tag = TAG)

            // Warn about tunneling services
            if (host.contains("ngrok") ||
                host.contains("localhost") ||
                host.contains("127.0.0.1") ||
                host.contains(".local")
            ) {
                Napier.w("⚠️ Detected tunneling/local service: $host", tag = TAG)
                Napier.w("FIDO2 may have issues with tunneling services", tag = TAG)
            }

            host
        } catch (e: Exception) {
            Napier.e("Failed to parse origin URL: $origin", e, tag = TAG)
            null
        }
    }

    /**
     * Build attestation options for FIDO2 request
     */
    private fun buildAttestationOptions(
        algoAddress: String,
        rpId: String,
        baseOptions: JSONObject = JSONObject(),
    ): JSONObject =
        baseOptions.apply {
            put("username", algoAddress)
            put("address", algoAddress) // Server needs this too!
            put("displayName", "Liquid Auth User")

            // Authenticator selection
            val authenticatorSelection =
                JSONObject().apply {
                    put("authenticatorAttachment", "platform")
                    put("userVerification", "required")
                    put("requireResidentKey", false)
                }
            put("authenticatorSelection", authenticatorSelection)

            // Relying Party
            val rp =
                JSONObject().apply {
                    put("id", rpId)
                    put("name", "Liquid Auth")
                }
            put("rp", rp)

            // Extensions
            val extensions =
                JSONObject().apply {
                    put("liquid", true)
                }
            put("extensions", extensions)
        }

    /**
     * Extract session ID from HTTP response cookies
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
