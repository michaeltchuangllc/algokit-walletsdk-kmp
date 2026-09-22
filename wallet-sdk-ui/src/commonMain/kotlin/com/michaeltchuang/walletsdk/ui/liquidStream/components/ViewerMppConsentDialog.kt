package com.michaeltchuang.walletsdk.ui.liquidStream.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.BudgetCap
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ConsentApproval
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.LiquidAuthViewerStateHolder
import kotlin.math.roundToLong

/**
 * Shared viewer consent and Session Vault funding flow.
 *
 * The payment rail requests consent through [LiquidAuthViewerStateHolder]; both Android and iOS
 * render this same dialog and resolve the same continuation, preventing platform-specific funding
 * behavior from drifting.
 */
@Composable
fun ViewerMppConsentDialog(stateHolder: LiquidAuthViewerStateHolder) {
    val pendingConsent by stateHolder.pendingViewerConsent.collectAsState()
    val isPaymentProcessing by stateHolder.isViewerPaymentProcessing.collectAsState()
    val consent = pendingConsent ?: return
    if (isPaymentProcessing) return

    val perSegmentMicro = consent.amount.toLongOrNull()?.coerceAtLeast(1L) ?: 1L
    val defaultTopUpMicro = 1_000_000L
    val defaultAmount = (defaultTopUpMicro / 1_000_000.0).toString()

    LiquidAuthSessionVaultModal(
        initialAmount = defaultAmount,
        quickAmounts = listOf(defaultAmount, "8.0"),
        currencyLabel = "USDC",
        isProcessing = isPaymentProcessing,
        isDismissible = false,
        onDismiss = {
            if (!isPaymentProcessing) {
                stateHolder.rejectViewerConsent()
            }
        },
        onTopUpAndStream = { enteredAmount ->
            if (isPaymentProcessing) return@LiquidAuthSessionVaultModal

            val depositMicro =
                ((enteredAmount.toDoubleOrNull() ?: (defaultTopUpMicro / 1_000_000.0)) * 1_000_000.0)
                    .roundToLong()
                    .coerceAtLeast(1L)
            val maxSegments = (depositMicro / perSegmentMicro).toInt().coerceAtLeast(1)

            stateHolder.approveViewerConsent(
                ConsentApproval(
                    approved = true,
                    autoPaySegments = true,
                    budgetCap = BudgetCap(amount = depositMicro.toString(), asset = "USDC"),
                    maxAutoPaySegments = maxSegments,
                ),
            )
        },
    )
}
