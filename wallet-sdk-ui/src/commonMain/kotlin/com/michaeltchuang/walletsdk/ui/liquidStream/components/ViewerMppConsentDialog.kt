package com.michaeltchuang.walletsdk.ui.liquidStream.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.BudgetCap
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ConsentApproval
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.LiquidAuthViewerStateHolder
import kotlinx.coroutines.launch
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
    var isVisible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(pendingConsent) {
        isVisible = pendingConsent != null
    }

    val consent = pendingConsent ?: return
    if (!isVisible) return

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
                isVisible = false
            }
        },
        onTopUpAndStream = { enteredAmount ->
            if (isPaymentProcessing) return@LiquidAuthSessionVaultModal

            val depositMicro =
                ((enteredAmount.toDoubleOrNull() ?: (defaultTopUpMicro / 1_000_000.0)) * 1_000_000.0)
                    .roundToLong()
                    .coerceAtLeast(1L)
            val maxSegments = (depositMicro / perSegmentMicro).toInt().coerceAtLeast(1)

            stateHolder.setViewerPaymentProcessing(true)
            scope.launch {
                try {
                    stateHolder.approveViewerConsent(
                        ConsentApproval(
                            approved = true,
                            autoPaySegments = true,
                            budgetCap = BudgetCap(amount = depositMicro.toString(), asset = "USDC"),
                            maxAutoPaySegments = maxSegments,
                        ),
                    )
                    isVisible = false
                } finally {
                    stateHolder.setViewerPaymentProcessing(false)
                }
            }
        },
    )
}
