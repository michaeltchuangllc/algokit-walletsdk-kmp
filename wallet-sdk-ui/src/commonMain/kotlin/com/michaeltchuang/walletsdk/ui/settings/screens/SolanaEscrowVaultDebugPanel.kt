package com.michaeltchuang.walletsdk.ui.settings.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.settings.utils.rememberSeedVaultTransactionSigner
import com.michaeltchuang.walletsdk.ui.settings.viewmodels.SolanaEscrowVaultDebugViewModel
import com.michaeltchuang.walletsdk.ui.settings.viewmodels.formatSolanaDebugSol
import org.koin.compose.viewmodel.koinViewModel

@Composable
internal fun SolanaEscrowVaultDebugPanel(onBusyChanged: (Boolean) -> Unit) {
    val viewModel = koinViewModel<SolanaEscrowVaultDebugViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sign = rememberSeedVaultTransactionSigner()
    LaunchedEffect(state.isLoading) { onBusyChanged(state.isLoading) }
    LaunchedEffect(viewModel) { viewModel.refresh() }
    val colors =
        OutlinedTextFieldDefaults.colors(
            focusedTextColor = AlgoKitTheme.colors.textMain,
            unfocusedTextColor = AlgoKitTheme.colors.textMain,
            focusedLabelColor = AlgoKitTheme.colors.textMain,
            unfocusedLabelColor = AlgoKitTheme.colors.textGray,
        )
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Solana ${state.cluster.ifBlank { "network" }}",
            style = MaterialTheme.typography.titleMedium,
            color = AlgoKitTheme.colors.textMain,
        )

        AddressDropdownField(
            label = "Viewer / Payer · Seed Vault",
            placeholder = "Select the depositing account",
            selectedAddress = state.payer,
            accountAddresses = state.accounts,
            enabled = !state.isLoading,
            colors = colors,
            allowNone = false,
            onAddressSelected = viewModel::selectPayer,
        )
        AddressDropdownField(
            label = "Creator / Payee · Seed Vault",
            placeholder = "Select the receiving account",
            selectedAddress = state.payee,
            accountAddresses = state.accounts,
            enabled = !state.isLoading,
            colors = colors,
            allowNone = false,
            onAddressSelected = viewModel::selectPayee,
        )
        if (state.accounts.size < 2) {
            Text("Import two Seed Vault accounts to test both roles.", color = AlgoKitTheme.colors.textGray)
        }
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Viewer voucher signer (no SOL needed):\n${state.payerSessionKey.ifBlank { "Not selected" }}",
                    color = AlgoKitTheme.colors.textMain,
                )
                Text(
                    "Creator fee payer / payee signer (creator's linked key) — pays settle fees and token-account rent:\n" +
                        state.payeeFeePayer.ifBlank { "Not selected" } +
                        (state.payeeFeePayerLamports?.let { "\nBalance: ${formatSolanaDebugSol(it)} SOL" } ?: ""),
                    color = AlgoKitTheme.colors.textMain,
                )
            }
        }
        AddressDropdownField(
            label = "Existing escrow channel",
            placeholder = "No open channel — deposit to open one",
            selectedAddress = state.channel?.address.orEmpty(),
            accountAddresses = state.channels.map { it.address },
            enabled = !state.isLoading,
            colors = colors,
            allowNone = false,
            onAddressSelected = viewModel::selectChannel,
        )
        OutlinedTextField(
            value = state.amount,
            onValueChange = viewModel::setAmount,
            label = { Text("Deposit / voucher increment (USDC)") },
            supportingText = { Text("Positive amount, up to 6 decimal places") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            enabled = !state.isLoading,
            colors = colors,
            modifier = Modifier.fillMaxWidth(),
        )
        HorizontalDivider()
        VaultAction("Refresh channels / balance", !state.isLoading, viewModel::refresh)
        VaultAction(
            if (state.channel == null) "Open & deposit" else "Top up",
            state.canRun,
        ) { viewModel.deposit(sign) }
        val hasChannel = state.canRun && state.channel != null
        VaultAction("Update voucher · linked hot wallet", hasChannel, viewModel::updateVoucher)
        VaultAction(
            "Refresh voucher blockhash (same amount)",
            hasChannel && state.pendingVoucher != null,
            viewModel::refreshVoucher,
        )
        VaultAction(
            "Settle · creator hot wallet pays fees",
            hasChannel && state.pendingVoucher != null,
            viewModel::settle,
        )
        VaultAction("Authorize viewer voucher signer (on-chain)", hasChannel) { viewModel.authorizeSigner(sign) }
        VaultAction("Revoke viewer voucher signer", hasChannel) { viewModel.revokeSigner(sign) }
        VaultAction("Authorize creator payee signer (on-chain)", hasChannel) { viewModel.authorizePayeeSigner(sign) }
        VaultAction("Revoke creator payee signer", hasChannel) { viewModel.revokePayeeSigner(sign) }
        VaultAction("Fund creator fee payer 0.01 SOL · creator", hasChannel) { viewModel.fundFeePayer(sign) }
        VaultAction("Close & refund · creator", hasChannel) { viewModel.close(sign) }
        VaultAction(
            "Close & refund · creator payee signer (hot wallet)",
            hasChannel && state.channel?.payeeSigner == state.payeeFeePayer,
            viewModel::closeWithPayeeSigner,
        )
        VaultAction("Request close · viewer", hasChannel) { viewModel.requestClose(sign) }
        VaultAction("Withdraw after 888 seconds", hasChannel) { viewModel.withdraw(sign) }
        if (state.isLoading) CircularProgressIndicator()
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.channel?.let { channel ->
                    Text(
                        "Remaining: ${formatDebugUsdc(channel.remainingDeposit)} USDC\n" +
                            "Settled: ${formatDebugUsdc(channel.lastSettled)} USDC\n" +
                            "Viewer voucher signer: ${if (channel.isSignerRevoked) "Revoked" else channel.authorizedSigner}\n" +
                            "Creator payee signer: ${if (channel.hasPayeeSigner) channel.payeeSigner else "Not set"}" +
                            (channel.withdrawAvailableAt?.let { "\nWithdraw available at Unix time: $it" } ?: ""),
                        color = AlgoKitTheme.colors.textMain,
                    )
                }
                state.pendingVoucher?.let {
                    Text(
                        "Pending cumulative voucher: ${formatDebugUsdc(it.cumulativeAmount)} USDC",
                        color = AlgoKitTheme.colors.textMain,
                    )
                }
                if (state.status.isNotBlank()) {
                    Text(state.status, color = AlgoKitTheme.colors.textMain)
                }
            }
        }
    }
}

@Composable
private fun VaultAction(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text(label)
    }
}

private fun formatDebugUsdc(amount: Long): String = "${amount / 1_000_000}.${(amount % 1_000_000).toString().padStart(6, '0')}"
