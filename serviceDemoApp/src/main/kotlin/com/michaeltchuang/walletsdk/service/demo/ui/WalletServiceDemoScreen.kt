package com.michaeltchuang.walletsdk.service.demo.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.michaeltchuang.walletsdk.service.demo.WalletScreens
import com.michaeltchuang.walletsdk.service.demo.WalletServiceConstants
import com.michaeltchuang.walletsdk.service.demo.data.model.AccountLite
import com.michaeltchuang.walletsdk.service.demo.data.model.SolanaAccount
import com.michaeltchuang.walletsdk.service.demo.data.model.SolanaSeed
import com.michaeltchuang.walletsdk.service.demo.ui.viewmodel.WalletServiceDemoViewModel

@Composable
fun WalletServiceDemoScreen(
    viewModel: WalletServiceDemoViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            // Title
            Text(
                text = "Wallet Service Demo",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (uiState.serviceConnected)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Service Status",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (uiState.serviceConnected) "✓ Connected" else "✗ Not Connected",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { viewModel.connectToService() },
                    modifier = Modifier.weight(1f),
                    enabled = !uiState.loading
                ) {
                    Text("Connect")
                }

                Button(
                    onClick = { viewModel.fetchAccounts() },
                    modifier = Modifier.weight(1f),
                    enabled = uiState.serviceConnected && !uiState.loading
                ) {
                    Text("Fetch Accounts")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Action Buttons Row 2
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Create Account Button
                Button(
                    onClick = { launchWalletActivity(context, WalletScreens.ONBOARDING) },
                    modifier = Modifier.weight(1f),
                    enabled = uiState.serviceConnected && !uiState.loading
                ) {
                    Text("Create/Manage")
                }

                // Settings Button
                Button(
                    onClick = { launchWalletActivity(context, WalletScreens.SETTINGS) },
                    modifier = Modifier.weight(1f),
                    enabled = uiState.serviceConnected && !uiState.loading
                ) {
                    Text("Settings")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Solana Seed Vault Button Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Fetch Solana Seeds Button
                Button(
                    onClick = { viewModel.refreshSolanaSeeds() },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    enabled = uiState.serviceConnected && !uiState.loading
                ) {
                    Text(
                        text = "Fetch Solana Accounts",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }

                // Authorize New Seed Button
                Button(
                    onClick = { viewModel.authorizeNewSeed() },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    enabled = uiState.serviceConnected && !uiState.loading && uiState.hasUnauthorizedSeeds
                ) {
                    Text(
                        text = "Authorize Seed",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Balance Button Row - only Refresh Balances button
            Button(
                onClick = { viewModel.fetchAllAccountBalances() },
                modifier = Modifier.fillMaxWidth(),
                enabled = uiState.serviceConnected && !uiState.loading && uiState.seeds.isNotEmpty()
            ) {
                Text("Refresh Balances")
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Loading Indicator
            if (uiState.loading) {
                CircularProgressIndicator()
            }

            // Error Message
            if (uiState.errorMessage != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = uiState.errorMessage ?: "",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Accounts List (Algorand)
            if (uiState.accounts.isNotEmpty()) {
                Text(
                    text = "Algorand Accounts (${uiState.accounts.size})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    uiState.accounts.forEach { account ->
                        AccountCard(account)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            // Solana Accounts List (Flat - All accounts from all seeds)
            if (uiState.seeds.isNotEmpty()) {
                // Calculate total accounts count
                val totalAccounts = uiState.seeds.sumOf { it.accounts.size }

                Text(
                    text = "Solana Accounts ($totalAccounts)",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth()
                )

                // Show total balance if available
                if (uiState.totalBalance != null) {
                    Text(
                        text = "Total Balance: %.4f SOL".format(uiState.totalBalance),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Show balance loading indicator
                if (uiState.balanceLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )
                    Text(
                        text = "Fetching on-chain balances...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Show balance error if any
                uiState.balanceError?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // ==================== SOL Transfer Section ====================
                Spacer(modifier = Modifier.height(24.dp))

                // Transfer Form - only show if there are accounts with balance
                val accountsWithBalance = uiState.seeds.flatMap { it.accounts }.filter { it.balance != null && it.balance > 0 }
                if (accountsWithBalance.isNotEmpty()) {
                    TransferSection(
                        accounts = accountsWithBalance,
                        onTransfer = { fromPublicKey, toPublicKey, amount ->
                            viewModel.createTransferTransaction(fromPublicKey, toPublicKey, amount)
                        },
                        transferLoading = uiState.transferLoading,
                        transferError = uiState.transferError,
                        lastTransferSignature = uiState.lastTransferSignature
                    )
                }

                if (uiState.hasUnauthorizedSeeds) {
                    Text(
                        text = "Unauthorized seeds available",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                uiState.seedVaultLimits?.let { limits ->
                    Text(
                        text = "Max Signing Requests: ${limits.maxSigningRequests}, " +
                               "Max Signatures: ${limits.maxRequestedSignatures}, " +
                               "Max Public Keys: ${limits.maxRequestedPublicKeys}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Flat list of all accounts from all seeds
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    uiState.seeds.forEach { seed ->
                        seed.accounts.forEach { account ->
                            FlatAccountCard(account = account, seedName = seed.name)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Launch the wallet overlay activity with the specified initial screen.
 */
private fun launchWalletActivity(context: android.content.Context, initialScreen: String) {
    val intent = Intent(WalletServiceConstants.ACTIVITY_ACTION).apply {
        putExtra(WalletServiceConstants.EXTRA_INITIAL_SCREEN, initialScreen)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

@Composable
fun AccountCard(account: AccountLite) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = account.customName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = account.address.take(10) + "..." + account.address.takeLast(10),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Balance: ${account.balance ?: "0"} microAlgos",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "Type: ${account.registrationType::class.simpleName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Displays a single Solana account in a flat list format.
 * Shows account name, balance, public key, and the seed it belongs to.
 */
@Composable
fun FlatAccountCard(
    account: SolanaAccount,
    seedName: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Account Name with Seed Tag
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = account.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )

                // Seed name tag
                Text(
                    text = seedName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier
                        .padding(start = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Public Key (truncated for display)
            Text(
                text = account.publicKeyEncoded.take(10) + "..." + account.publicKeyEncoded.takeLast(10),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Full public key (smaller, for reference)
            Text(
                text = account.publicKeyEncoded,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Balance display
            when {
                account.isBalanceLoading -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "Loading balance...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                account.balance != null -> {
                    Text(
                        text = "%.4f SOL".format(account.balance),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                else -> {
                    Text(
                        text = "Balance: --",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Transfer section UI component for sending SOL between accounts.
 */
@Composable
fun TransferSection(
    accounts: List<SolanaAccount>,
    onTransfer: (fromPublicKey: String, toPublicKey: String, amount: Double) -> Unit,
    transferLoading: Boolean,
    transferError: String?,
    lastTransferSignature: String?
) {
    var selectedFromAccount by remember { mutableStateOf<SolanaAccount?>(accounts.firstOrNull()) }
    var recipientPublicKey by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Send SOL",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            // From Account Selection
            Text(
                text = "From Account",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )

            // Show all available accounts as selectable cards
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                accounts.forEach { account ->
                    val isSelected = selectedFromAccount?.publicKeyEncoded == account.publicKeyEncoded
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surface
                        ),
                        onClick = { selectedFromAccount = account }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = account.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                                Text(
                                    text = account.publicKeyEncoded.take(8) + "..." + account.publicKeyEncoded.takeLast(8),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = "%.4f SOL".format(account.balance ?: 0.0),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // To Address Input
            OutlinedTextField(
                value = recipientPublicKey,
                onValueChange = { recipientPublicKey = it },
                label = { Text("Recipient Public Key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !transferLoading
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Amount Input
            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it },
                label = { Text("Amount (SOL)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !transferLoading,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Transfer Button
            Button(
                onClick = {
                    val amount = amountText.toDoubleOrNull() ?: 0.0
                    selectedFromAccount?.let { fromAccount ->
                        if (recipientPublicKey.isNotBlank() && amount > 0) {
                            onTransfer(fromAccount.publicKeyEncoded, recipientPublicKey, amount)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !transferLoading &&
                          selectedFromAccount != null &&
                          recipientPublicKey.isNotBlank() &&
                          amountText.toDoubleOrNull() != null &&
                          amountText.toDoubleOrNull()!! > 0
            ) {
                if (transferLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Create Transfer Transaction")
                }
            }

            // Transfer Error
            transferError?.let { error ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // Last Transfer Success
            lastTransferSignature?.let { signature ->
                Spacer(modifier = Modifier.height(8.dp))
                val context = LocalContext.current
                val explorerUrl = "https://explorer.solana.com/tx/${signature}?cluster=devnet"
                
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(explorerUrl))
                        context.startActivity(intent)
                    }
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Transfer Sent Successfully!",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Signature: ${signature.take(20)}...${signature.takeLast(20)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Tap to view on Solana Explorer",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
