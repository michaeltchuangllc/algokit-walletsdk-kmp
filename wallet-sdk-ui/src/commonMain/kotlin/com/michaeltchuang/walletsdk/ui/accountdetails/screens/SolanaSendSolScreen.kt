package com.michaeltchuang.walletsdk.ui.accountdetails.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.michaeltchuang.walletsdk.core.solana.domain.SendSolanaSolUseCase
import com.michaeltchuang.walletsdk.core.solana.domain.SolanaClusters
import com.michaeltchuang.walletsdk.ui.accountdetails.viewmodels.SolanaSendSolViewModel
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.AlgoKitTopBar
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.button.AlgoKitButtonState
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.button.AlgoKitPrimaryButton
import com.michaeltchuang.walletsdk.ui.settings.utils.isSeedVaultTransactionSigningSupported
import com.michaeltchuang.walletsdk.ui.settings.utils.rememberSeedVaultTransactionSigner
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.koin.compose.viewmodel.koinViewModel

/** Sends SOL from a Seed Vault account; one Seed Vault approval per send. */
@Composable
fun SolanaSendSolScreen(
    address: String,
    prefillReceiver: String?,
    onBack: () -> Unit,
    showSnackBar: (String) -> Unit,
) {
    val viewModel: SolanaSendSolViewModel = koinViewModel()
    val state by viewModel.state.collectAsState()
    val sign = rememberSeedVaultTransactionSigner()

    LaunchedEffect(address) { viewModel.load(address, prefillReceiver) }
    LaunchedEffect(Unit) {
        viewModel.viewEvent.collect { event ->
            when (event) {
                is SolanaSendSolViewModel.ViewEvent.Message -> showSnackBar(event.text)
                is SolanaSendSolViewModel.ViewEvent.Sent -> showSnackBar(event.text)
            }
        }
    }

    SolanaSendSolContent(
        state = state,
        signingSupported = isSeedVaultTransactionSigningSupported,
        onBack = onBack,
        onReceiverChange = viewModel::setReceiver,
        onAmountChange = viewModel::setAmount,
        onUseSessionKey = viewModel::useSessionKey,
        onMax = viewModel::setMax,
        onRefreshBalance = viewModel::refreshBalance,
        onSend = { viewModel.send(sign) },
    )
}

@Composable
private fun SolanaSendSolContent(
    state: SolanaSendSolViewModel.ViewState,
    signingSupported: Boolean,
    onBack: () -> Unit,
    onReceiverChange: (String) -> Unit,
    onAmountChange: (String) -> Unit,
    onUseSessionKey: () -> Unit,
    onMax: () -> Unit,
    onRefreshBalance: () -> Unit,
    onSend: () -> Unit,
) {
    val colors =
        OutlinedTextFieldDefaults.colors(
            focusedTextColor = AlgoKitTheme.colors.textMain,
            unfocusedTextColor = AlgoKitTheme.colors.textMain,
            focusedLabelColor = AlgoKitTheme.colors.textMain,
            unfocusedLabelColor = AlgoKitTheme.colors.textGray,
        )
    val amountLamports = SendSolanaSolUseCase.parseSol(state.amount)
    val canSend =
        signingSupported &&
            !state.isSending &&
            state.receiver.isNotBlank() &&
            (amountLamports ?: 0L) > 0L

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(AlgoKitTheme.colors.background),
    ) {
        AlgoKitTopBar(
            title = "Send SOL",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            onClick = onBack,
        )
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, AlgoKitTheme.colors.layerGrayLighter, RoundedCornerShape(12.dp))
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Seed Vault balance",
                        modifier = Modifier.weight(1f),
                        color = AlgoKitTheme.colors.textGray,
                        fontSize = 12.sp,
                    )
                    if (state.isBalanceLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = AlgoKitTheme.colors.positive,
                        )
                    } else {
                        Text(
                            text = "Refresh",
                            modifier = Modifier.clickable(onClick = onRefreshBalance),
                            color = AlgoKitTheme.colors.positive,
                            fontSize = 13.sp,
                        )
                    }
                }
                Text(
                    text = "${formatUnits(state.balanceLamports, 9)} SOL",
                    color = AlgoKitTheme.colors.textMain,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(text = state.sender, color = AlgoKitTheme.colors.textGray, fontSize = 12.sp)
            }

            OutlinedTextField(
                value = state.receiver,
                onValueChange = onReceiverChange,
                label = { Text("Recipient Solana address") },
                singleLine = true,
                enabled = !state.isSending,
                colors = colors,
                modifier = Modifier.fillMaxWidth(),
            )
            state.sessionKeyAddress?.let { key ->
                Text(
                    text =
                        if (state.receiver == key) {
                            "✓ Sending to this account's session key (pays Liquid Stream settlement fees)"
                        } else {
                            "Use my session key ${key.take(6)}…${key.takeLast(4)}"
                        },
                    modifier = Modifier.clickable(enabled = !state.isSending, onClick = onUseSessionKey),
                    color = AlgoKitTheme.colors.positive,
                    fontSize = 13.sp,
                )
            }

            OutlinedTextField(
                value = state.amount,
                onValueChange = onAmountChange,
                label = { Text("Amount (SOL)") },
                supportingText = {
                    Text(
                        "Network fee ${SendSolanaSolUseCase.formatSol(SendSolanaSolUseCase.FEE_LAMPORTS)} SOL. " +
                            "New accounts need at least " +
                            "${SendSolanaSolUseCase.formatSol(SolanaClusters.RENT_EXEMPT_MINIMUM_LAMPORTS)} SOL.",
                    )
                },
                trailingIcon = {
                    Text(
                        text = "Max",
                        modifier =
                            Modifier
                                .padding(end = 12.dp)
                                .clickable(enabled = !state.isSending && state.balanceLamports != null, onClick = onMax),
                        color = AlgoKitTheme.colors.positive,
                        fontSize = 13.sp,
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                enabled = !state.isSending,
                colors = colors,
                modifier = Modifier.fillMaxWidth(),
            )

            if (!signingSupported) {
                Text(
                    text = "Seed Vault signing isn't available on this device.",
                    color = AlgoKitTheme.colors.negative,
                    fontSize = 13.sp,
                )
            }

            AlgoKitPrimaryButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onSend,
                text = "Send with Seed Vault",
                state =
                    when {
                        state.isSending -> AlgoKitButtonState.PROGRESS
                        canSend -> AlgoKitButtonState.ENABLED
                        else -> AlgoKitButtonState.DISABLED
                    },
            )

            state.lastSignature?.let { sig ->
                Text(
                    text = "Last transaction: $sig",
                    color = AlgoKitTheme.colors.textGray,
                    fontSize = 12.sp,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Preview
@Composable
private fun SolanaSendSolPreview() {
    AlgoKitTheme {
        SolanaSendSolContent(
            state =
                SolanaSendSolViewModel.ViewState(
                    sender = "Owner1111111111111111111111111111111111111",
                    receiver = "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin",
                    amount = "0.01",
                    sessionKeyAddress = "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin",
                    balanceLamports = 1_250_000_000,
                ),
            signingSupported = true,
            onBack = {},
            onReceiverChange = {},
            onAmountChange = {},
            onUseSessionKey = {},
            onMax = {},
            onRefreshBalance = {},
            onSend = {},
        )
    }
}
