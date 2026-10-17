package com.michaeltchuang.seedvault.demo.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.michaeltchuang.seedvault.demo.SeedVaultServiceConstants
import com.michaeltchuang.seedvault.demo.data.model.SeedVaultSeed
import com.michaeltchuang.seedvault.demo.data.model.SolanaAccount
import com.michaeltchuang.seedvault.demo.data.repository.AlgorandRepository
import com.michaeltchuang.seedvault.demo.ui.viewmodel.WalletServiceDemoUiState
import com.solanamobile.seedvault.WalletContractV1
import com.michaeltchuang.seedvault.demo.ui.viewmodel.WalletServiceDemoViewModel

@Composable
fun WalletServiceDemoScreen(
    viewModel: WalletServiceDemoViewModel = viewModel(),
    onAuthorizeApp: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Text(
                text = "Seed Vault Service Demo",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp)
            )

            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Algorand") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Solana") }
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top
            ) {
                when (selectedTab) {
                    0 -> AlgorandTab(uiState, viewModel, onAuthorizeApp)
                    else -> SolanaTab(uiState, viewModel)
                }
            }
        }
    }
}

/**
 * Falcon-1024 accounts from Seed Vault Service (over AIDL) on Algorand TestNet. This app talks to
 * algod; the vault only builds and signs.
 */
@Composable
private fun ColumnScope.AlgorandTab(
    uiState: WalletServiceDemoUiState,
    viewModel: WalletServiceDemoViewModel,
    onAuthorizeApp: () -> Unit
) {
    val context = LocalContext.current

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
                text = "Seed Vault Service",
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
            onClick = { viewModel.fetchSeedVaultSeeds() },
            modifier = Modifier.weight(1f),
            enabled = uiState.serviceConnected && !uiState.loading
        ) {
            Text("Fetch Seeds")
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Asks the user in Seed Vault Service which seeds this app may use
        Button(
            onClick = onAuthorizeApp,
            modifier = Modifier.weight(1f),
            enabled = uiState.serviceConnected && !uiState.loading
        ) {
            Text("Authorize App")
        }

        // Opens the Seed Vault Service app to create, import and manage seeds
        Button(
            onClick = { launchSeedVault(context) },
            modifier = Modifier.weight(1f),
            enabled = !uiState.loading
        ) {
            Text("Open Seed Vault")
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    Button(
        onClick = { viewModel.refreshAlgorandBalances() },
        modifier = Modifier.fillMaxWidth(),
        enabled = !uiState.loading && !uiState.algorandBalanceLoading && uiState.seedVaultSeeds.isNotEmpty()
    ) {
        Text("Refresh Balances")
    }

    Spacer(modifier = Modifier.height(16.dp))

    StatusMessages(uiState)

    uiState.signResult?.let { ResultCard(it) }

    uiState.algorandPaymentResult?.let { result ->
        ResultCard(
            text = result,
            onOpen = uiState.algorandPaymentTxId?.let { txId ->
                {
                    val url = AlgorandRepository.EXPLORER_TX_URL + txId
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            }
        )
    }

    if (uiState.algorandBalanceLoading) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
    }

    if (uiState.seedVaultSeeds.isNotEmpty()) {
        Text(
            text = "Seed Vault Seeds (${uiState.seedVaultSeeds.size})",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            uiState.seedVaultSeeds.forEach { seed ->
                SeedVaultSeedCard(
                    seed = seed,
                    balanceMicroAlgos = uiState.algorandBalances[seed.address],
                    enabled = !uiState.loading,
                    onSign = { viewModel.signDemoMessage(seed) },
                    onSendZero = { viewModel.sendZeroAlgoPayment(seed) }
                )
            }
        }
    }
}

/**
 * Accounts from the Solana Mobile Seed Vault on Solana DevNet. Independent of Seed Vault Service,
 * whose Falcon-1024 keys cannot sign Solana transactions.
 */
@Composable
private fun ColumnScope.SolanaTab(
    uiState: WalletServiceDemoUiState,
    viewModel: WalletServiceDemoViewModel
) {
    SolanaSeedVaultStatusCard(uiState)

    Spacer(modifier = Modifier.height(16.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = { viewModel.refreshSolanaSeeds() },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            enabled = !uiState.loading
        ) {
            Text(
                text = "Fetch Solana Accounts",
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }

        Button(
            onClick = { viewModel.authorizeNewSeed() },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            enabled = !uiState.loading && uiState.hasUnauthorizedSeeds
        ) {
            Text(
                text = "Authorize Seed",
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    Button(
        onClick = { viewModel.fetchAllAccountBalances() },
        modifier = Modifier.fillMaxWidth(),
        enabled = !uiState.loading && !uiState.balanceLoading && uiState.seeds.isNotEmpty()
    ) {
        Text("Refresh Balances")
    }

    Spacer(modifier = Modifier.height(16.dp))

    StatusMessages(uiState)

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

/**
 * Status of the Solana Mobile Seed Vault. It is not a bound service: this app reaches it through
 * the Seed Vault SDK (content provider and activities), so there is nothing to connect to. What
 * can be checked is whether the Seed Vault app is installed, whether this app holds its
 * permission, and which seeds are authorized.
 */
@Composable
private fun SolanaSeedVaultStatusCard(uiState: WalletServiceDemoUiState) {
    val context = LocalContext.current
    val isInstalled = try {
        context.packageManager.getPackageInfo(SOLANA_SEED_VAULT_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
    val hasPermission =
        context.checkSelfPermission(WalletContractV1.PERMISSION_ACCESS_SEED_VAULT) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(WalletContractV1.PERMISSION_ACCESS_SEED_VAULT_PRIVILEGED) == PackageManager.PERMISSION_GRANTED
    val isReady = isInstalled && hasPermission

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isReady)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Solana Seed Vault",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (isInstalled) "✓ Seed Vault app installed" else "✗ Seed Vault app not installed",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = if (hasPermission) "✓ Permission granted" else "✗ Permission not granted",
                style = MaterialTheme.typography.bodyLarge
            )
            if (isReady) {
                val accountCount = uiState.seeds.sumOf { it.accounts.size }
                Text(
                    text = "${uiState.seeds.size} authorized seed(s), $accountCount account(s)" +
                        if (uiState.hasUnauthorizedSeeds) ", more seeds available to authorize" else "",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

private const val SOLANA_SEED_VAULT_PACKAGE = "com.solanamobile.seedvaultimpl"

/** Loading spinner and error card shared by both tabs. */
@Composable
private fun ColumnScope.StatusMessages(uiState: WalletServiceDemoUiState) {
    if (uiState.loading) {
        CircularProgressIndicator()
    }

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
}

@Composable
private fun ColumnScope.ResultCard(
    text: String,
    onOpen: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = text,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            if (onOpen != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onOpen) {
                    Text("View on TestNet explorer")
                }
            }
        }
    }
    Spacer(modifier = Modifier.height(16.dp))
}

/**
 * Open the Seed Vault Service app.
 */
private fun launchSeedVault(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(SeedVaultServiceConstants.PACKAGE)
    if (intent == null) {
        Toast.makeText(context, "Seed Vault Service is not installed", Toast.LENGTH_SHORT).show()
        return
    }
    context.startActivity(intent)
}

@Composable
fun SeedVaultSeedCard(
    seed: SeedVaultSeed,
    balanceMicroAlgos: Long?,
    enabled: Boolean,
    onSign: () -> Unit,
    onSendZero: () -> Unit
) {
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = seed.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = seed.address.take(10) + "..." + seed.address.takeLast(10),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = seed.address,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )

                Button(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Falcon-1024 Address", seed.address)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Address copied!", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .height(28.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "Copy",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Public key: ${seed.publicKey.take(24)}...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (balanceMicroAlgos != null) {
                    "Balance: %.6f ALGO (TestNet)".format(balanceMicroAlgos / AlgorandRepository.MICRO_ALGOS_PER_ALGO)
                } else {
                    "Balance: tap Refresh Balances"
                },
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onSign, modifier = Modifier.weight(1f), enabled = enabled) {
                    Text("Sign bytes")
                }
                Button(onClick = onSendZero, modifier = Modifier.weight(1f), enabled = enabled) {
                    Text("Send 0 ALGO")
                }
            }
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
    val context = LocalContext.current
    
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

            // Full public key with copy button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = account.publicKeyEncoded,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                
                // Copy button
                Button(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Solana Address", account.publicKeyEncoded)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Address copied!", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .height(28.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "Copy",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

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
                    Log.d("WalletServiceDemo", "Displaying balance for ${account.name}: ${account.balance} SOL")
                    Text(
                        text = "%.4f SOL".format(account.balance),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                else -> {
                    // Balance fetch failed (null but not loading)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "⚠",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = "Balance: Error (tap Refresh)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
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
                        if (recipientPublicKey.isNotBlank() && amount >= 0) {
                            onTransfer(fromAccount.publicKeyEncoded, recipientPublicKey, amount)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !transferLoading &&
                          selectedFromAccount != null &&
                          recipientPublicKey.isNotBlank() &&
                          amountText.toDoubleOrNull() != null &&
                          amountText.toDoubleOrNull()!! >= 0
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
