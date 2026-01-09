package com.michaeltchuang.walletsdk.ui.liquidAuth.domain.usecases

import android.util.Log
import androidx.fragment.app.FragmentActivity
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.usecases.AuthenticateWithBiometricsUseCase
import com.michaeltchuang.walletsdk.ui.liquidAuth.AnswerViewModel
import foundation.algorand.provider.Message
import foundation.algorand.provider.avm.models.ResponseMessage
import foundation.algorand.provider.avm.models.SignTransactionsParams
import foundation.algorand.provider.avm.models.SignTransactionsResult
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Use case for processing biometric transaction signing
 *
 * Orchestrates the complete flow of:
 * 1. Authenticating with biometrics
 * 2. Processing the transaction signing
 * 3. Handling success/error states
 *
 * This provides a clean separation between UI logic and business logic
 */
class ProcessBiometricTransactionSigningUseCase(
    private val authenticateWithBiometricsUseCase: AuthenticateWithBiometricsUseCase,
) {
    companion object {
        private const val TAG = "BiometricTxnSigningUseCase"
    }

    /**
     * Result of the biometric transaction signing process
     */
    sealed class Result {
        data class Success(
            val resultMessage: ResponseMessage,
            val signResult: SignTransactionsResult,
        ) : Result()

        data class Cancelled(
            val reason: String,
        ) : Result()

        data class Error(
            val message: String,
            val exception: Exception?,
        ) : Result()
    }

    /**
     * Process transaction signing with biometric authentication
     *
     * @param activity The activity context
     * @param viewModel The view model for business logic
     * @param params Transaction parameters
     * @param message Original message for processing
     * @return Result indicating success, cancellation, or error
     */
    @OptIn(ExperimentalEncodingApi::class)
    suspend operator fun invoke(
        activity: FragmentActivity,
        viewModel: AnswerViewModel,
        params: SignTransactionsParams,
        message: Message,
    ): Result {
        // Step 1: Authenticate with biometrics
        val biometricSuccess = authenticateWithBiometricsUseCase(activity, params)

        if (!biometricSuccess) {
            Log.w(TAG, "Biometric authentication cancelled or failed")
            return Result.Cancelled("User cancelled biometric authentication")
        }

        return try {
            Log.d(TAG, "========================================")
            Log.d(TAG, "✅ BIOMETRIC AUTHENTICATION SUCCESSFUL")
            Log.d(TAG, "Processing transaction signing...")
            Log.d(TAG, "========================================")

            // Step 2: Process transaction signing through ViewModel
            val resultMessage = viewModel.handleMessage(message) as ResponseMessage

            // Step 3: Extract and validate result
            when (val result = resultMessage.result) {
                is SignTransactionsResult -> {
                    Log.d(TAG, "✅ Transaction signing completed successfully")
                    Log.d(TAG, "Number of signed transactions: ${result.stxns.size}")
                    Result.Success(resultMessage, result)
                }
                else -> {
                    Log.e(TAG, "Unknown result type: ${result?.javaClass?.simpleName}")
                    Result.Error("Unknown result type", null)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during transaction signing", e)
            Result.Error("Transaction signing failed: ${e.message}", e)
        }
    }
}
