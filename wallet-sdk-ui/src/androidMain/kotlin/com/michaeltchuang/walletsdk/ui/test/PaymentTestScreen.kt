package com.michaeltchuang.walletsdk.ui.test

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.developer_settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.AlgoKitTopBar
import com.michaeltchuang.walletsdk.ui.settings.domain.localization.localizedStringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Manual test screen for Session Vault payment operations.
 *
 * Provides three manual actions:
 * 1. Add (deposit) amount to the Session Vault.
 * 2. Fetch the Session Vault remaining balance.
 * 3. Settle the latest voucher – transferring earned funds to the creator.
 */
@Composable
fun PaymentTestScreen(navController: NavController) {
    val viewModel: PaymentTestViewModel = koinViewModel()

    val viewerAddress by viewModel.viewerAddress.collectAsStateWithLifecycle()
    val creatorAddress by viewModel.creatorAddress.collectAsStateWithLifecycle()
    val depositAmount by viewModel.depositAmountUsdc.collectAsStateWithLifecycle()
    val remainingBalance by viewModel.remainingBalance.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(AlgoKitTheme.colors.background)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ── Title ────────────────────────────────────────────────────────────
        AlgoKitTopBar(
            title = localizedStringResource(Res.string.developer_settings),
            onClick = { navController.popBackStack() },
        )

        Text(
            text = "Manually test Session Vault operations between a viewer and a creator.",
            style = MaterialTheme.typography.bodyMedium,
            color = AlgoKitTheme.colors.textGray,
        )

        HorizontalDivider()

        // ── Address inputs ────────────────────────────────────────────────────
        Text(
            text = "Addresses",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = AlgoKitTheme.colors.textMain,
        )

        OutlinedTextField(
            value = viewerAddress,
            onValueChange = viewModel::onViewerAddressChanged,
            label = { Text("Viewer Address") },
            placeholder = { Text("Algorand address of the viewer (payer)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Next,
                ),
            enabled = !isLoading,
        )

        OutlinedTextField(
            value = creatorAddress,
            onValueChange = viewModel::onCreatorAddressChanged,
            label = { Text("Creator Address") },
            placeholder = { Text("Algorand address of the creator (payee)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Next,
                ),
            enabled = !isLoading,
        )

        OutlinedTextField(
            value = depositAmount,
            onValueChange = viewModel::onDepositAmountChanged,
            label = { Text("Deposit Amount (USDC)") },
            placeholder = { Text("e.g. 1.0") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done,
                ),
            enabled = !isLoading,
        )

        HorizontalDivider()

        // ── Action buttons ────────────────────────────────────────────────────
        Text(
            text = "Actions",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = AlgoKitTheme.colors.textMain,
        )

        Button(
            onClick = viewModel::addAmountToSessionVault,
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Add Amount to Session Vault")
        }

        Button(
            onClick = viewModel::fetchSessionVaultRemainingBalance,
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Fetch Session Vault Balance")
        }
        Button(
            onClick = viewModel::updateVoucher,
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Update Voucher")
        }
        Button(
            onClick = viewModel::verifyVoucherSignature,
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Verify Voucher")
        }

        Button(
            onClick = viewModel::settleAmount,
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Settle Amount to Creator")
        }

        HorizontalDivider()

        // ── Results ───────────────────────────────────────────────────────────
        Text(
            text = "Results",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = AlgoKitTheme.colors.textMain,
        )

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        remainingBalance?.let { balance ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Session Vault Remaining Balance",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${balance / 1_000_000.0} USDC",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "$balance microUSDC",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                    )
                }
            }
        }

        statusMessage?.let { message ->
            val isSuccess = message.startsWith("✅")
            val isError = message.startsWith("❌")
            val containerColor =
                when {
                    isSuccess -> MaterialTheme.colorScheme.primaryContainer
                    isError -> MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = containerColor),
            ) {
                Text(
                    text = message,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        // Extra bottom spacing so content is not clipped by nav bar
        Spacer(modifier = Modifier.height(32.dp))
    }
}
