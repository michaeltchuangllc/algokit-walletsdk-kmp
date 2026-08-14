package com.michaeltchuang.walletsdk.ui.settings.screens

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.developer_settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.AlgoKitTopBar
import com.michaeltchuang.walletsdk.ui.settings.domain.localization.localizedStringResource
import com.michaeltchuang.walletsdk.ui.settings.viewmodels.EscrowSessionVaultDebugViewModel
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun EscrowSessionVaultDebugToolScreen(navController: NavHostController) {
    val viewModel: EscrowSessionVaultDebugViewModel = koinViewModel()
    val viewState by viewModel.state.collectAsStateWithLifecycle()
    val content = viewState as EscrowSessionVaultDebugViewModel.ViewState.Content
    var statusMessage by remember { mutableStateOf<String?>(null) }
    val accountAddresses = content.accountAddresses
    val viewerAddress = content.viewerAddress
    val viewerAddress2 = content.viewerAddress2
    val viewerAddress3 = content.viewerAddress3
    val creatorAddress = content.creatorAddress
    val depositAmount = content.depositAmountUsdc
    val remainingBalance = content.remainingBalance
    val isLoading = content.isLoading
    val canRunVaultActions = content.canRunVaultActions
    val selectedAddresses =
        listOf(creatorAddress, viewerAddress, viewerAddress2, viewerAddress3)
            .filter { it.isNotBlank() }
            .toSet()
    LaunchedEffect(viewModel) {
        viewModel.viewEvent.collect { event ->
            when (event) {
                is EscrowSessionVaultDebugViewModel.ViewEvent.ShowStatusMessage -> {
                    statusMessage = event.message
                }
            }
        }
    }
    val textFieldColors =
        OutlinedTextFieldDefaults.colors(
            focusedTextColor = AlgoKitTheme.colors.textMain,
            unfocusedTextColor = AlgoKitTheme.colors.textMain,
            disabledTextColor = AlgoKitTheme.colors.textGray,
            focusedLabelColor = AlgoKitTheme.colors.textMain,
            unfocusedLabelColor = AlgoKitTheme.colors.textGray,
            disabledLabelColor = AlgoKitTheme.colors.textGray,
            focusedPlaceholderColor = AlgoKitTheme.colors.textGray,
            unfocusedPlaceholderColor = AlgoKitTheme.colors.textGray,
            disabledPlaceholderColor = AlgoKitTheme.colors.textGray,
            cursorColor = AlgoKitTheme.colors.textMain,
        )

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(AlgoKitTheme.colors.background)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
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

        Text(
            text = "Addresses",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = AlgoKitTheme.colors.textMain,
        )

        AddressDropdownField(
            label = "Creator Address",
            placeholder = "Select the creator (payee)",
            selectedAddress = creatorAddress,
            accountAddresses = accountAddresses.filterNot { it in (selectedAddresses - creatorAddress) },
            enabled = !isLoading,
            colors = textFieldColors,
            onAddressSelected = viewModel::onCreatorAddressChanged,
        )

        AddressDropdownField(
            label = "Viewer 1 Address",
            placeholder = "Select the viewer 1 (payer)",
            selectedAddress = viewerAddress,
            accountAddresses = accountAddresses.filterNot { it in (selectedAddresses - viewerAddress) },
            enabled = !isLoading,
            colors = textFieldColors,
            onAddressSelected = viewModel::onViewerAddressChanged,
        )

        AddressDropdownField(
            label = "Viewer 2 Address",
            placeholder = "Select the viewer 2",
            selectedAddress = viewerAddress2,
            accountAddresses = accountAddresses.filterNot { it in (selectedAddresses - viewerAddress2) },
            enabled = !isLoading,
            colors = textFieldColors,
            onAddressSelected = viewModel::onViewerAddress2Changed,
        )

        AddressDropdownField(
            label = "Viewer 3 Address",
            placeholder = "Select the viewer 3",
            selectedAddress = viewerAddress3,
            accountAddresses = accountAddresses.filterNot { it in (selectedAddresses - viewerAddress3) },
            enabled = !isLoading,
            colors = textFieldColors,
            onAddressSelected = viewModel::onViewerAddress3Changed,
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
            colors = textFieldColors,
        )

        HorizontalDivider()

        Text(
            text = "Actions",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = AlgoKitTheme.colors.textMain,
        )

        Button(
            onClick = viewModel::addAmountToSessionVault,
            enabled = !isLoading && canRunVaultActions,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Add Amount to Session Vault")
        }

        Button(
            onClick = viewModel::fetchSessionVaultRemainingBalance,
            enabled = !isLoading && canRunVaultActions,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Fetch Session Vault Balance")
        }

        Button(
            onClick = viewModel::updateVoucher,
            enabled = !isLoading && canRunVaultActions,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Update Voucher")
        }

        Button(
            onClick = viewModel::registerSettlementLogicSig,
            enabled = !isLoading && canRunVaultActions,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Register Settlement LogicSig")
        }

        Button(
            onClick = viewModel::settleAmount,
            enabled = !isLoading && canRunVaultActions,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Settle Amount")
        }

        Button(
            onClick = viewModel::closeSessionVault,
            enabled = !isLoading && canRunVaultActions,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Close")
        }

        Button(
            onClick = viewModel::requestCloseSessionVault,
            enabled = !isLoading && canRunVaultActions,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Request Close")
        }

        Button(
            onClick = viewModel::requestWithdraw,
            enabled = !isLoading && canRunVaultActions,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Request Withdraw")
        }

        HorizontalDivider()

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

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun AddressDropdownField(
    label: String,
    placeholder: String,
    selectedAddress: String,
    accountAddresses: List<String>,
    enabled: Boolean,
    colors: androidx.compose.material3.TextFieldColors,
    onAddressSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = selectedAddress,
            onValueChange = {},
            label = { Text(label) },
            placeholder = { Text(if (accountAddresses.isEmpty() && selectedAddress.isBlank()) "No available accounts" else placeholder) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            readOnly = true,
            enabled = enabled,
            colors = colors,
            trailingIcon = {
                Text(if (expanded) "▲" else "▼")
            },
        )
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clickable(enabled = enabled) { expanded = true },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxWidth(),
        ) {
            DropdownMenuItem(
                text = { Text("None") },
                onClick = {
                    onAddressSelected("")
                    expanded = false
                },
            )
            accountAddresses.forEach { address ->
                DropdownMenuItem(
                    text = { Text(address) },
                    onClick = {
                        onAddressSelected(address)
                        expanded = false
                    },
                )
            }
        }
    }
}
