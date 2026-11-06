package com.michaeltchuang.walletsdk.ui.settings.screens

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.algorand_session_source
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.developer_settings
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.escrow_authorize_creator
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.escrow_authorize_viewer
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.escrow_close_hot_wallet
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.escrow_creator_key
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.escrow_delegate_details
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.escrow_fund_creator
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.escrow_fund_payee_box
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.escrow_local_signer
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.escrow_open_deposit
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.escrow_refresh_complete
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.escrow_refresh_no_channel
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.escrow_refresh_progress
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.escrow_refresh_status
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.escrow_revoke_creator
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.escrow_revoke_viewer
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.escrow_status_authorized
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.escrow_status_creator
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.escrow_status_different_key
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.escrow_status_logic_sig
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.escrow_status_no_channel
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.escrow_status_not_authorized
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.escrow_status_not_registered
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.escrow_status_not_set
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.escrow_status_registered
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.escrow_status_unknown
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.escrow_status_viewer
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.escrow_top_up
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.escrow_viewer_key
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.michaeltchuang.walletsdk.ui.settings.utils.isSeedVaultTransactionSigningSupported
import com.michaeltchuang.walletsdk.ui.settings.viewmodels.EscrowSessionVaultDebugViewModel
import com.michaeltchuang.walletsdk.ui.settings.viewmodels.EscrowSessionVaultDebugViewModel.ResultMessage
import com.michaeltchuang.walletsdk.ui.signing.screens.rememberAlgorandSeedVaultSigner
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun EscrowSessionVaultDebugToolScreen(navController: NavHostController) {
    var solanaSelected by rememberSaveable { mutableStateOf(false) }
    var isBusy by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().background(AlgoKitTheme.colors.background),
    ) {
        AlgoKitTopBar(
            title = localizedStringResource(Res.string.developer_settings),
            onClick = { navController.popBackStack() },
        )
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FilterChip(
                selected = !solanaSelected,
                onClick = { solanaSelected = false },
                enabled = !isBusy,
                label = { Text("Algorand") },
            )
            FilterChip(
                selected = solanaSelected,
                onClick = { solanaSelected = true },
                enabled = !isBusy && isSeedVaultTransactionSigningSupported,
                label = { Text("Solana · Seed Vault") },
            )
        }
        if (solanaSelected) {
            SolanaEscrowVaultDebugPanel(onBusyChanged = { isBusy = it })
        } else {
            AlgorandEscrowVaultDebugPanel(onBusyChanged = { isBusy = it })
        }
    }
}

@Composable
private fun AlgorandEscrowVaultDebugPanel(onBusyChanged: (Boolean) -> Unit) {
    val viewModel: EscrowSessionVaultDebugViewModel = koinViewModel()
    val sign = rememberAlgorandSeedVaultSigner()
    val viewState by viewModel.state.collectAsStateWithLifecycle()
    val content = viewState as EscrowSessionVaultDebugViewModel.ViewState.Content
    val statusMessage =
        when (val result = content.resultMessage) {
            is ResultMessage.Text -> result.value
            ResultMessage.Refreshing -> localizedStringResource(Res.string.escrow_refresh_progress)
            ResultMessage.Refreshed -> localizedStringResource(Res.string.escrow_refresh_complete)
            ResultMessage.NoChannel -> localizedStringResource(Res.string.escrow_refresh_no_channel)
            null -> null
        }
    val accountAddresses = content.accountAddresses
    val viewerAddress = content.viewerAddress
    val viewerAddress2 = content.viewerAddress2
    val viewerAddress3 = content.viewerAddress3
    val creatorAddress = content.creatorAddress
    val depositAmount = content.depositAmountUsdc
    val remainingBalance = content.remainingBalance
    val isLoading = content.isLoading || content.isRefreshingStatus
    LaunchedEffect(isLoading) { onBusyChanged(isLoading) }
    val canRunVaultActions = content.canRunVaultActions
    val selectedAddresses =
        listOf(creatorAddress, viewerAddress, viewerAddress2, viewerAddress3)
            .filter { it.isNotBlank() }
            .toSet()
    LaunchedEffect(viewModel) {
        viewModel.refreshSignerStatus()
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

        AlgorandSignerStatus(content)

        Text(
            text = "Actions",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = AlgoKitTheme.colors.textMain,
        )

        val canAct = !isLoading && canRunVaultActions
        AlgorandVaultAction(
            localizedStringResource(Res.string.escrow_refresh_status),
            !isLoading,
            onClick = viewModel::onRefreshSignerStatus,
        )
        AlgorandVaultAction(
            localizedStringResource(if (content.hasChannel) Res.string.escrow_top_up else Res.string.escrow_open_deposit),
            canAct && content.authorization != null,
            onClick = { viewModel.addAmountToSessionVault(sign) },
        )
        AlgorandVaultAction(
            "Open + authorize + register (one approval)",
            canAct && content.authorization != null,
            visible = !content.hasChannel,
            onClick = { viewModel.openAuthorizeAndRegisterSession(sign) },
        )
        AlgorandVaultAction(
            "Update Voucher",
            canAct && content.authorization?.viewerSignerAuthorized == true,
            visible = content.hasChannel,
            onClick = viewModel::updateVoucher,
        )
        AlgorandVaultAction(
            "Register Settlement LogicSig",
            canAct && content.authorization?.viewerSignerAuthorized == true,
            visible = content.showRegisterLogicSig,
            onClick = { viewModel.registerSettlementLogicSig(sign) },
        )
        AlgorandVaultAction(
            localizedStringResource(Res.string.escrow_authorize_viewer),
            canAct,
            visible = content.showAuthorizeViewer,
            onClick = { viewModel.authorizeSigner(sign) },
        )
        AlgorandVaultAction(
            localizedStringResource(Res.string.escrow_revoke_viewer),
            canAct,
            visible = content.showRevokeViewer,
            onClick = { viewModel.revokeSigner(sign) },
        )
        AlgorandVaultAction(
            localizedStringResource(Res.string.escrow_fund_payee_box),
            canAct,
            visible = content.hasChannel && content.authorization?.authorizedPayeeSigner == null,
            onClick = { viewModel.fundPayeeSignerBox(sign) },
        )
        AlgorandVaultAction(
            localizedStringResource(Res.string.escrow_authorize_creator),
            canAct,
            visible = content.showAuthorizeCreator,
            onClick = { viewModel.authorizePayeeSigner(sign) },
        )
        AlgorandVaultAction(
            localizedStringResource(Res.string.escrow_revoke_creator),
            canAct,
            visible = content.showRevokeCreator,
            onClick = { viewModel.revokePayeeSigner(sign) },
        )
        AlgorandVaultAction(
            localizedStringResource(Res.string.escrow_fund_creator),
            canAct && content.creatorAddress in content.seedVaultAddresses,
            visible = content.hasChannel && content.creatorAddress in content.seedVaultAddresses,
            onClick = { viewModel.fundCreatorSigner(sign) },
        )

        Text(
            localizedStringResource(Res.string.escrow_delegate_details),
            color = AlgoKitTheme.colors.textGray,
        )
        AlgorandVaultAction(
            "Settle Amount",
            canAct && content.canSettle,
            visible = content.hasChannel,
            onClick = viewModel::settleAmount,
        )
        AlgorandVaultAction(
            "Close",
            canAct,
            visible = content.hasChannel,
            onClick = { viewModel.closeSessionVault(sign) },
        )
        AlgorandVaultAction(
            localizedStringResource(Res.string.escrow_close_hot_wallet),
            canAct,
            visible = content.creatorSignerAuthorized,
            onClick = viewModel::closeWithPayeeSigner,
        )
        AlgorandVaultAction(
            "Request Close",
            canAct,
            visible = content.hasChannel && (content.authorization?.closeRequestedAt ?: 0L) == 0L,
            onClick = { viewModel.requestCloseSessionVault(sign) },
        )
        AlgorandVaultAction(
            "Request Withdraw",
            canAct,
            visible = content.hasChannel && (content.authorization?.closeRequestedAt ?: 0L) > 0L,
            onClick = { viewModel.requestWithdraw(sign) },
        )

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
private fun AlgorandVaultAction(
    label: String,
    enabled: Boolean,
    visible: Boolean = true,
    onClick: () -> Unit,
) {
    if (!visible) return

    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(label)
    }
}

@Composable
private fun AlgorandSignerStatus(content: EscrowSessionVaultDebugViewModel.ViewState.Content) {
    val status = content.authorization
    val unavailable =
        when {
            status == null -> Res.string.escrow_status_unknown
            !status.channelExists -> Res.string.escrow_status_no_channel
            else -> null
        }
    val viewerStatus =
        localizedStringResource(
            unavailable ?: if (status?.viewerSignerAuthorized == true) {
                Res.string.escrow_status_authorized
            } else {
                Res.string.escrow_status_not_authorized
            },
        )
    val logicSigStatus =
        localizedStringResource(
            unavailable ?: when {
                status?.settlementRegistered == true -> Res.string.escrow_status_registered
                status?.hasSettlementLogicSig == true -> Res.string.escrow_status_different_key
                else -> Res.string.escrow_status_not_registered
            },
        )
    val creatorStatus =
        localizedStringResource(
            unavailable ?: when {
                content.creatorSignerAuthorized -> Res.string.escrow_status_authorized
                status?.authorizedPayeeSigner != null -> Res.string.escrow_status_not_authorized
                else -> Res.string.escrow_status_not_set
            },
        )
    val unknown = localizedStringResource(Res.string.escrow_status_unknown)
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                localizedStringResource(Res.string.escrow_viewer_key, content.viewerSessionSigner.ifBlank { unknown }),
                color = AlgoKitTheme.colors.textMain,
            )
            Text(
                localizedStringResource(
                    if (content.viewerAddress in
                        content.seedVaultAddresses
                    ) {
                        Res.string.algorand_session_source
                    } else {
                        Res.string.escrow_local_signer
                    },
                ),
                color = AlgoKitTheme.colors.textGray,
            )
            Text(localizedStringResource(Res.string.escrow_status_viewer, viewerStatus), color = AlgoKitTheme.colors.textMain)
            Text(localizedStringResource(Res.string.escrow_status_logic_sig, logicSigStatus), color = AlgoKitTheme.colors.textMain)
            Text(
                localizedStringResource(Res.string.escrow_creator_key, content.creatorPayeeSigner.ifBlank { unknown }),
                color = AlgoKitTheme.colors.textMain,
            )
            Text(
                localizedStringResource(
                    if (content.creatorAddress in
                        content.seedVaultAddresses
                    ) {
                        Res.string.algorand_session_source
                    } else {
                        Res.string.escrow_local_signer
                    },
                ),
                color = AlgoKitTheme.colors.textGray,
            )
            Text(localizedStringResource(Res.string.escrow_status_creator, creatorStatus), color = AlgoKitTheme.colors.textMain)
            status?.authorizedPayeeSigner?.let { Text(it, color = AlgoKitTheme.colors.textMain) }
        }
    }
}

@Composable
internal fun AddressDropdownField(
    label: String,
    placeholder: String,
    selectedAddress: String,
    accountAddresses: List<String>,
    enabled: Boolean,
    colors: androidx.compose.material3.TextFieldColors,
    allowNone: Boolean = true,
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
            if (allowNone) {
                DropdownMenuItem(
                    text = { Text("None") },
                    onClick = {
                        onAddressSelected("")
                        expanded = false
                    },
                )
            }
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
