package com.michaeltchuang.walletsdk.ui.onboarding.screens

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.already_imported
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.continue_text
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.seed_vault_loading_algorand_accounts
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.seed_vault_loading_solana_accounts
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.seed_vault_select_algorand_accounts
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.seed_vault_select_solana_accounts
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.select_all
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.toUpperCase
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.michaeltchuang.walletsdk.core.foundation.utils.Chain
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.AlgoKitTopBar
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.button.AlgoKitButtonState
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.button.AlgoKitPrimaryButton
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.text.AlgoKitBodyText
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.text.AlgoKitHeadlineText
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.text.AlgoKitHighlightedGrayText
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.text.AlgoKitTitleText
import com.michaeltchuang.walletsdk.ui.onboarding.viewmodels.SeedVaultAccountsViewModel
import com.michaeltchuang.walletsdk.ui.settings.domain.localization.localizedStringResource
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ImportSeedVaultAccountsScreen(
    viewModel: SeedVaultAccountsViewModel = koinViewModel(),
    navController: NavController = rememberNavController(),
    accountType: Chain = Chain.ALGORAND,
    selectedSeedIds: Set<String> = emptySet(),
    showSnackBar: (message: String) -> Unit,
    onAccountsImported: () -> Unit,
) {
    val viewState by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(selectedSeedIds, accountType) {
        viewModel.loadAccounts(accountType, selectedSeedIds)
    }

    LaunchedEffect(viewModel.viewEvent) {
        viewModel.viewEvent.collect { event ->
            when (event) {
                is SeedVaultAccountsViewModel.ViewEvent.AccountsImported -> {
                    onAccountsImported()
                }
                is SeedVaultAccountsViewModel.ViewEvent.Error -> {
                    showSnackBar(event.message)
                }
            }
        }
    }

    when (val currentState = viewState) {
        is SeedVaultAccountsViewModel.ViewState.Idle -> Unit
        is SeedVaultAccountsViewModel.ViewState.Loading -> LoadingStateContent(accountType)
        is SeedVaultAccountsViewModel.ViewState.Content ->
            ContentStateContent(
                viewModel = viewModel,
                state = currentState,
                accountType = accountType,
                onBackClick = { navController.popBackStack() },
            )
        is SeedVaultAccountsViewModel.ViewState.Error ->
            ErrorStateContent(
                message = currentState.message,
                onBackClick = { navController.popBackStack() },
                onRetry = { viewModel.loadAccounts(accountType, selectedSeedIds) },
            )
    }
}

@Composable
private fun LoadingStateContent(accountType: Chain) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        AlgoKitBodyText(
            text =
                localizedStringResource(
                    if (accountType == Chain.ALGORAND) {
                        Res.string.seed_vault_loading_algorand_accounts
                    } else {
                        Res.string.seed_vault_loading_solana_accounts
                    },
                ),
        )
    }
}

@Composable
private fun ErrorStateContent(
    message: String,
    onBackClick: () -> Unit,
    onRetry: () -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = AlgoKitTheme.colors.background,
        bottomBar = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
            ) {
                AlgoKitPrimaryButton(
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth(),
                    text = "Retry",
                    state = AlgoKitButtonState.ENABLED,
                )
            }
        },
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 24.dp),
        ) {
            AlgoKitTopBar(onClick = onBackClick)

            Spacer(modifier = Modifier.height(24.dp))

            AlgoKitHeadlineText(
                text = "Failed to load accounts",
            )

            Spacer(modifier = Modifier.height(16.dp))

            AlgoKitBodyText(
                text = message,
                color = AlgoKitTheme.colors.textGray,
            )
        }
    }
}

@Composable
private fun ContentStateContent(
    viewModel: SeedVaultAccountsViewModel,
    state: SeedVaultAccountsViewModel.ViewState.Content,
    accountType: Chain,
    onBackClick: () -> Unit = {},
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = AlgoKitTheme.colors.background,
        bottomBar = {
            val isPrimaryButtonEnabled =
                state.selectedAddresses.isNotEmpty() && !state.isImporting
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
            ) {
                AlgoKitPrimaryButton(
                    onClick = { viewModel.importSelectedAccounts() },
                    modifier = Modifier.fillMaxWidth(),
                    text = localizedStringResource(Res.string.continue_text),
                    state =
                        if (isPrimaryButtonEnabled) {
                            AlgoKitButtonState.ENABLED
                        } else {
                            AlgoKitButtonState.DISABLED
                        },
                )
            }
        },
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 24.dp),
        ) {
            AlgoKitTopBar(onClick = onBackClick)

            Spacer(modifier = Modifier.height(24.dp))

            TitleText(accountType)
            DescriptionText(state.accounts.size)

            Spacer(modifier = Modifier.height(34.dp))

            ListHeaderContainer(
                state = state,
                onSelectAllAccounts = { viewModel.selectAllAccounts() },
                onUnselectAllAccounts = { viewModel.unselectAllAccounts() },
            )

            LazyColumn(
                modifier = Modifier.weight(1f),
            ) {
                itemsIndexed(state.accounts) { index, account ->
                    AddressItem(
                        selectedAddresses = state.selectedAddresses,
                        account = account,
                        onCheckedChange = { isChecked ->
                            viewModel.toggleAccountSelection(account.address, isChecked)
                        },
                    )
                    if (index != state.accounts.lastIndex) {
                        HorizontalDivider(
                            color = AlgoKitTheme.colors.layerGrayLighter,
                            thickness = 1.dp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TitleText(accountType: Chain = Chain.ALGORAND) {
    AlgoKitHeadlineText(
        text =
            localizedStringResource(
                if (accountType == Chain.ALGORAND) {
                    Res.string.seed_vault_select_algorand_accounts
                } else {
                    Res.string.seed_vault_select_solana_accounts
                },
            ),
    )
}

@Composable
private fun DescriptionText(accountCount: Int) {
    val addressWord = if (accountCount == 1) "address" else "addresses"
    AlgoKitBodyText(
        modifier = Modifier.padding(top = 8.dp),
        text = "Select the addresses you want to import ($accountCount $addressWord)",
        color = AlgoKitTheme.colors.textGray,
    )
}

@Composable
private fun ListHeaderContainer(
    state: SeedVaultAccountsViewModel.ViewState.Content,
    onSelectAllAccounts: () -> Unit,
    onUnselectAllAccounts: () -> Unit,
) {
    val notImportedCount = state.accounts.count { !it.isImported }
    val allSelected = state.selectedAddresses.size == notImportedCount && notImportedCount > 0
    val isSelectAllEnabled = notImportedCount > 0

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        AlgoKitTitleText(
            text = "${state.accounts.size} ${if (state.accounts.size == 1) "address" else "addresses"}",
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AlgoKitBodyText(
                text = localizedStringResource(Res.string.select_all),
                color = AlgoKitTheme.colors.textGray,
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Custom checkbox to match the design
            Surface(
                modifier = Modifier.size(24.dp),
                shape =
                    androidx.compose.foundation.shape
                        .RoundedCornerShape(4.dp),
                color = if (allSelected) AlgoKitTheme.colors.textMain else Color.Transparent,
                border =
                    androidx.compose.foundation.BorderStroke(
                        width = 1.dp,
                        color = if (allSelected) AlgoKitTheme.colors.textMain else AlgoKitTheme.colors.layerGray,
                    ),
                onClick = {
                    if (isSelectAllEnabled) {
                        if (allSelected) {
                            onUnselectAllAccounts()
                        } else {
                            onSelectAllAccounts()
                        }
                    }
                },
            ) {
                if (allSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Selected",
                        tint = Color.White,
                        modifier = Modifier.padding(2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AddressItem(
    selectedAddresses: Set<String>,
    account: SeedVaultAccountsViewModel.SeedVaultAccountItem,
    onCheckedChange: (Boolean) -> Unit,
) {
    val isSelected = selectedAddresses.contains(account.address)

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Address on the left
        AlgoKitTitleText(
            modifier = Modifier.weight(1f),
            text = formatShortAddress(account.address),
        )

        if (account.isImported) {
            // Already imported badge on the right
            Surface(
                modifier =
                    Modifier
                        .clip(
                            androidx.compose.foundation.shape
                                .RoundedCornerShape(16.dp),
                        ),
                color = AlgoKitTheme.colors.layerGrayLighter,
            ) {
                AlgoKitBodyText(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    text =
                        localizedStringResource(Res.string.already_imported)
                            .toUpperCase(Locale.current),
                    color = AlgoKitTheme.colors.textGray,
                )
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Derivation path displayed as a badge
                AlgoKitHighlightedGrayText(
                    text = chainBadgeText(account),
                )
                Spacer(modifier = Modifier.width(16.dp))

                // Custom checkbox on the right
                Surface(
                    modifier = Modifier.size(24.dp),
                    shape =
                        androidx.compose.foundation.shape
                            .RoundedCornerShape(4.dp),
                    color = if (isSelected) AlgoKitTheme.colors.textMain else Color.Transparent,
                    border =
                        androidx.compose.foundation.BorderStroke(
                            width = 1.dp,
                            color = if (isSelected) AlgoKitTheme.colors.textMain else AlgoKitTheme.colors.layerGray,
                        ),
                    onClick = { onCheckedChange(!isSelected) },
                ) {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Selected",
                            tint = Color.White,
                            modifier = Modifier.padding(2.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun formatShortAddress(address: String): String =
    if (address.length >= 8) {
        "${address.take(4)}...${address.takeLast(4)}"
    } else {
        address
    }

private fun chainBadgeText(account: SeedVaultAccountsViewModel.SeedVaultAccountItem): String =
    Chain.fromId(account.chainId)?.ticker ?: formatDerivationPath(account.derivationPath)

private fun formatDerivationPath(derivationPath: String): String =
    derivationPath
        .split("/")
        .firstNotNullOfOrNull { Chain.fromId(it.removeSuffix("'")) }
        ?.ticker
        ?: derivationPath

@Preview
@Composable
private fun ImportSeedVaultAccountsScreenPreview() {
    val fakeViewState =
        SeedVaultAccountsViewModel.ViewState.Content(
            accounts =
                listOf(
                    SeedVaultAccountsViewModel.SeedVaultAccountItem(
                        address = "IYRGNKJDFK3LFJD4K8GFJ4K3BFVKMY3E",
                        accountName = "Main Account",
                        derivationPath = "m/44/501/0/0",
                        isImported = false,
                        chainId = Chain.SOLANA.id,
                    ),
                    SeedVaultAccountsViewModel.SeedVaultAccountItem(
                        address = "B7DFJHGK8LMNPQR9STUVWXYZ123456789",
                        accountName = "Secondary Account",
                        derivationPath = "m/44/501/1/0",
                        isImported = false,
                        chainId = Chain.SOLANA.id,
                    ),
                ),
            selectedAddresses = setOf("IYRGNKJDFK3LFJD4K8GFJ4K3BFVKMY3E"),
        )

    AlgoKitTheme {
        ContentStatePreview(fakeViewState)
    }
}

@Composable
private fun ContentStatePreview(state: SeedVaultAccountsViewModel.ViewState.Content) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = AlgoKitTheme.colors.background,
        bottomBar = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
            ) {
                AlgoKitPrimaryButton(
                    onClick = { },
                    modifier = Modifier.fillMaxWidth(),
                    text = localizedStringResource(Res.string.continue_text),
                    state = AlgoKitButtonState.ENABLED,
                )
            }
        },
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 24.dp),
        ) {
            AlgoKitTopBar(onClick = { })

            Spacer(modifier = Modifier.height(24.dp))

            TitleText()
            DescriptionText(state.accounts.size)

            Spacer(modifier = Modifier.height(34.dp))

            ListHeaderContainerPreview(state)

            LazyColumn(
                modifier = Modifier.weight(1f),
            ) {
                itemsIndexed(state.accounts) { index, account ->
                    AddressItemPreview(
                        selectedAddresses = state.selectedAddresses,
                        account = account,
                    )
                    if (index != state.accounts.lastIndex) {
                        HorizontalDivider(
                            color = AlgoKitTheme.colors.layerGrayLighter,
                            thickness = 1.dp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ListHeaderContainerPreview(state: SeedVaultAccountsViewModel.ViewState.Content) {
    val notImportedCount = state.accounts.count { !it.isImported }
    val allSelected = state.selectedAddresses.size == notImportedCount && notImportedCount > 0
    val isSelectAllEnabled = notImportedCount > 0

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        AlgoKitTitleText(
            text = "${state.accounts.size} ${if (state.accounts.size == 1) "address" else "addresses"}",
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AlgoKitBodyText(
                text = localizedStringResource(Res.string.select_all),
                color = AlgoKitTheme.colors.textGray,
            )

            Spacer(modifier = Modifier.width(8.dp))

            Surface(
                modifier = Modifier.size(24.dp),
                shape =
                    androidx.compose.foundation.shape
                        .RoundedCornerShape(4.dp),
                color = if (allSelected) AlgoKitTheme.colors.textMain else Color.Transparent,
                border =
                    androidx.compose.foundation.BorderStroke(
                        width = 1.dp,
                        color = if (allSelected) AlgoKitTheme.colors.textMain else AlgoKitTheme.colors.layerGray,
                    ),
                onClick = {},
            ) {
                if (allSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Selected",
                        tint = Color.White,
                        modifier = Modifier.padding(2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AddressItemPreview(
    selectedAddresses: Set<String>,
    account: SeedVaultAccountsViewModel.SeedVaultAccountItem,
) {
    val isSelected = selectedAddresses.contains(account.address)

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        AlgoKitTitleText(
            modifier = Modifier.weight(1f),
            text = formatShortAddress(account.address),
        )

        if (account.isImported) {
            Surface(
                modifier =
                    Modifier
                        .clip(
                            androidx.compose.foundation.shape
                                .RoundedCornerShape(16.dp),
                        ),
                color = AlgoKitTheme.colors.layerGrayLighter,
            ) {
                AlgoKitBodyText(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    text =
                        localizedStringResource(Res.string.already_imported)
                            .toUpperCase(Locale.current),
                    color = AlgoKitTheme.colors.textGray,
                )
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                chainBadgeText(account).let { name ->
                    AlgoKitHighlightedGrayText(
                        text = name,
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                }

                Surface(
                    modifier = Modifier.size(24.dp),
                    shape =
                        androidx.compose.foundation.shape
                            .RoundedCornerShape(4.dp),
                    color = if (isSelected) AlgoKitTheme.colors.textMain else Color.Transparent,
                    border =
                        androidx.compose.foundation.BorderStroke(
                            width = 1.dp,
                            color = if (isSelected) AlgoKitTheme.colors.textMain else AlgoKitTheme.colors.layerGray,
                        ),
                    onClick = {},
                ) {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Selected",
                            tint = Color.White,
                            modifier = Modifier.padding(2.dp),
                        )
                    }
                }
            }
        }
    }
}
