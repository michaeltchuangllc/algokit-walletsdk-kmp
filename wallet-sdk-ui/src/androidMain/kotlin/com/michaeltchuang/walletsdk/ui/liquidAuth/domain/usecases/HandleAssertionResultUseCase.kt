package com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases

import com.michaeltchuang.walletsdk.core.liquidAuth.auth.fido2.WebAuthnCredential
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.usecases.AssertionApiUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.AnswerViewModel
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Use case for handling FIDO2 assertion (authentication) result
 *
 * Processes the provider-neutral authentication credential and handles:
 * - Liquid extension JSON creation
 * - Server submission
 * - Previous counter extraction after server verification
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
            val credential: WebAuthnCredential,
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
     * Submit the assertion credential for server verification
     *
     * @param credential The credential returned by the authentication provider
     * @param viewModel The ViewModel for API calls
     * @return Result indicating success, cancellation, or error
     */
    suspend operator fun invoke(
        credential: WebAuthnCredential,
        viewModel: AnswerViewModel,
    ): Result {
        try {
            val authMessage =
                viewModel.authMessage.value
                    ?: return Result.Error("Authentication message is missing")
            val challengeSignature =
                viewModel.currentChallenge
                    ?: return Result.Error("Challenge signature is missing")

            val liquidExtJSON =
                buildLiquidExtensionJson(
                    accountType = viewModel.getAccountTypeForFido2(viewModel.accountAddress.value),
                    requestId = authMessage.requestId,
                    accountAddress = viewModel.accountAddress.value,
                    publicKey = viewModel.getAccountPublicKey(viewModel.accountAddress.value),
                    challengeSignature = challengeSignature,
                )

            Napier.d("Posting authentication assertion to server...", tag = TAG)

            return assertionApiUseCase
                .postAssertionResult(
                    authMessage.origin,
                    viewModel.userAgent,
                    credential,
                    liquidExtJSON,
                ).use { response ->
                    if (!response.isSuccessful) {
                        return Result.Error(
                            "Authentication failed: ${response.code} ${response.message}",
                        )
                    }

                    val responseBody = response.body.string()
                    val prevCounter = extractPrevCounter(responseBody, credential.id)
                    Napier.d("Authentication successful", tag = TAG)
                    Result.Success(
                        credential = credential,
                        responseBody = responseBody,
                        prevCounter = prevCounter,
                    )
                }
        } catch (e: CancellationException) {
            throw e
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Napier.w("Failed to extract prevCounter from response", e, tag = TAG)
            0
        }
    }
}
