package com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases

import android.os.Build
import com.michaeltchuang.walletsdk.core.liquidAuth.auth.fido2.WebAuthnCredential
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.usecases.AttestationApiUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.AnswerViewModel
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Submits a provider-neutral registration credential with the Liquid wallet extension.
 * The platform caller is responsible for saving the credential once after success.
 */
class HandleAttestationResultUseCase(
    private val attestationApiUseCase: AttestationApiUseCase,
) {
    companion object {
        private const val TAG = "HandleAttestationResultUseCase"
    }

    sealed class Result {
        data class Success(
            val credential: WebAuthnCredential,
            val responseBody: String,
        ) : Result()

        data class Cancelled(
            val message: String,
        ) : Result()

        data class Error(
            val message: String,
        ) : Result()
    }

    suspend operator fun invoke(
        credential: WebAuthnCredential,
        viewModel: AnswerViewModel,
    ): Result {
        return try {
            val challenge =
                viewModel.currentChallenge
                    ?: return Result.Error("Challenge signature is missing")
            val authMessage =
                viewModel.authMessage.value
                    ?: return Result.Error("Authentication message is missing")
            val liquidExtJSON =
                buildLiquidExtensionJson(
                    algoAddress = viewModel.accountAddress.value,
                    requestId = authMessage.requestId,
                    currentChallenge = challenge,
                    viewModel = viewModel,
                )

            attestationApiUseCase
                .postAttestationResult(
                    authMessage.origin,
                    viewModel.userAgent,
                    credential,
                    liquidExtJSON,
                ).use { response ->
                    if (!response.isSuccessful) {
                        return Result.Error(
                            "Registration failed: ${response.code} - Check server logs",
                        )
                    }
                    val responseBody = response.body.string()
                    Napier.d("Registration successful", tag = TAG)
                    Result.Success(credential, responseBody)
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Napier.e("Error processing attestation", e, tag = TAG)
            Result.Error("Error processing attestation: ${e.message}")
        }
    }

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
