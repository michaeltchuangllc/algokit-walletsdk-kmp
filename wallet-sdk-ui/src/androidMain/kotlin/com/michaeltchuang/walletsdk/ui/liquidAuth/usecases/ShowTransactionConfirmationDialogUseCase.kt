package com.michaeltchuang.walletsdk.ui.liquidAuth.usecases

import android.content.Context
import androidx.appcompat.app.AlertDialog
import foundation.algorand.provider.avm.models.SignTransactionsParams

/**
 * Use case for showing transaction confirmation dialog
 *
 * Provides a clean interface for displaying transaction confirmation dialogs
 * with proper formatting and user-friendly messaging.
 */
class ShowTransactionConfirmationDialogUseCase {

    /**
     * Show a confirmation dialog for transaction signing
     *
     * @param context The context for showing the dialog
     * @param params Transaction parameters to display
     * @param onConfirm Callback when user confirms
     * @param onCancel Callback when user cancels
     */
    operator fun invoke(
        context: Context,
        params: SignTransactionsParams,
        onConfirm: () -> Unit,
        onCancel: () -> Unit,
    ) {
        val title = buildTitle(params)
        val message = buildMessage(params)

        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
                onConfirm()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                onCancel()
            }
            .setCancelable(false)
            .create()
            .show()
    }

    /**
     * Build dialog title based on number of transactions
     */
    private fun buildTitle(params: SignTransactionsParams): String {
        return if (params.txns.size == 1) {
            "Sign Transaction"
        } else {
            "Sign ${params.txns.size} Transactions"
        }
    }

    /**
     * Build dialog message with transaction details
     */
    private fun buildMessage(params: SignTransactionsParams): String {
        return buildString {
            append("Do you want to sign the following transaction(s)?\n\n")
            append("Provider: ${params.providerId}\n")
            append("Number of transactions: ${params.txns.size}\n")
        }
    }
}
