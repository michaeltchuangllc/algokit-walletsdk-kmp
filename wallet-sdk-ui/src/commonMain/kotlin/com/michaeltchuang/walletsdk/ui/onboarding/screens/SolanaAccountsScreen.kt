package com.michaeltchuang.walletsdk.ui.onboarding.screens

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.already_imported
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.continue_text
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.select_all
import androidx.compose.foundation.background
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
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.AlgoKitTopBar
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.button.AlgoKitButtonState
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.button.AlgoKitPrimaryButton
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.text.AlgoKitBodyText
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.text.AlgoKitHeadlineText
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.text.AlgoKitHighlightedGrayText
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.text.AlgoKitTitleText
import com.michaeltchuang.walletsdk.ui.onboarding.viewmodels.SolanaAccountsViewModel
import com.michaeltchuang.walletsdk.ui.settings.domain.localization.localizedStringResource
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SolanaAccountsScreen(
    viewModel: SolanaAccountsViewModel = koinViewModel(),
    navController: NavController = rememberNavController(),
    selectedSeedIds: Set<String> = emptySet(),
    showSnackBar: (message: String) -> Unit,
    onAccountsImported: () -> Unit,
) {
    val viewState by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(selectedSeedIds) {
        viewModel.loadSolanaAccounts(selectedSeedIds)
    }

    LaunchedEffect(viewModel.viewEvent) {
        viewModel.viewEvent.collect { event ->
            when (event) {
                is SolanaAccountsViewModel.ViewEvent.AccountsImported -> {
                    onAccountsImported()
                }
                is SolanaAccountsViewModel.ViewEvent.Error -> {
                    showSnackBar(event.message)
                }
            }
        }
    }

    when (val currentState = viewState) {
        is SolanaAccountsViewModel.ViewState.Idle -> Unit
        is SolanaAccountsViewModel.ViewState.Loading -> LoadingStateContent()
        is SolanaAccountsViewModel.ViewState.Content ->
            ContentStateContent(
                viewModel = viewModel,
                state = currentState,
                onBackClick = { navController.popBackStack() },
            )
        is SolanaAccountsViewModel.ViewState.Error ->
            ErrorStateContent(
                message = currentState.message,
                onBackClick = { navController.popBackStack() },
                onRetry = { viewModel.loadSolanaAccounts(selectedSeedIds) },
            )
    }
}

@Composable
private fun LoadingStateContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        AlgoKitBodyText(text = "Loading Solana accounts...")
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
    viewModel: SolanaAccountsViewModel,
    state: SolanaAccountsViewModel.ViewState.Content,
    onBackClick: () -> Unit = {},
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = AlgoKitTheme.colors.background,
        bottomBar = {
            val isPrimaryButtonEnabled =
                state.selectedAddresses.isNotEmpty()
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

            TitleText()
            DescriptionText(state.solanaAccounts.size)

            Spacer(modifier = Modifier.height(34.dp))

            ListHeaderContainer(
                state = state,
                onSelectAllAccounts = { viewModel.selectAllAccounts() },
                onUnselectAllAccounts = { viewModel.unselectAllAccounts() },
            )

            LazyColumn(
                modifier = Modifier.weight(1f),
            ) {
                itemsIndexed(state.solanaAccounts) { index, account ->
                    AddressItem(
                        selectedAddresses = state.selectedAddresses,
                        account = account,
                        onCheckedChange = { isChecked ->
                            viewModel.toggleAccountSelection(account.address, isChecked)
                        },
                    )
                    if (index != state.solanaAccounts.lastIndex) {
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
private fun TitleText() {
    AlgoKitHeadlineText(
        text = "Select solana accounts to add",
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
    state: SolanaAccountsViewModel.ViewState.Content,
    onSelectAllAccounts: () -> Unit,
    onUnselectAllAccounts: () -> Unit,
) {
    val notImportedCount = state.solanaAccounts.count { !it.isImported }
    val allSelected = state.selectedAddresses.size == notImportedCount && notImportedCount > 0
    val isSelectAllEnabled = notImportedCount > 0

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        AlgoKitTitleText(
            text = "${state.solanaAccounts.size} ${if (state.solanaAccounts.size == 1) "address" else "addresses"}",
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
    account: SolanaAccountsViewModel.SolanaAccountItem,
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
                    text = formatDerivationPath(account.derivationPath),
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

/**
 * Formats a Solana address to short form (first 4 + ... + last 4)
 * Example: HN7cABqLq46Es1jh92dQQisAq662SmxELLLsHHe4YWrH -> HN7c...YWrH
 */
private fun formatShortAddress(address: String): String =
    if (address.length >= 8) {
        "${address.take(4)}...${address.takeLast(4)}"
    } else {
        address
    }

/**
 * Formats a derivation path to show just the account index
 * Example: m/44/501/0/0 -> 0, m/44/501/1/0 -> 1
 */
private fun formatDerivationPath(derivationPath: String): String {
    val parts = derivationPath.split("/")
    return if (parts.size >= 4) {
        parts[3] // Return the account index (4th element, 0-indexed)
    } else {
        derivationPath
    }
}

@Preview
@Composable
private fun SolanaAccountsScreenPreview() {
    val fakeViewState =
        SolanaAccountsViewModel.ViewState.Content(
            solanaAccounts =
                listOf(
                    SolanaAccountsViewModel.SolanaAccountItem(
                        address = "IYRGNKJDFK3LFJD4K8GFJ4K3BFVKMY3E",
                        accountName = "Main Account",
                        derivationPath = "m/44/501/0/0",
                        isImported = false,
                    ),
                    SolanaAccountsViewModel.SolanaAccountItem(
                        address = "B7DFJHGK8LMNPQR9STUVWXYZ123456789",
                        accountName = "Secondary Account",
                        derivationPath = "m/44/501/1/0",
                        isImported = false,
                    ),
                ),
            selectedAddresses = setOf("IYRGNKJDFK3LFJD4K8GFJ4K3BFVKMY3E"),
        )

    AlgoKitTheme {
        ContentStatePreview(fakeViewState)
    }
}

@Composable
private fun ContentStatePreview(state: SolanaAccountsViewModel.ViewState.Content) {
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
            DescriptionText(state.solanaAccounts.size)

            Spacer(modifier = Modifier.height(34.dp))

            ListHeaderContainerPreview(state)

            LazyColumn(
                modifier = Modifier.weight(1f),
            ) {
                itemsIndexed(state.solanaAccounts) { index, account ->
                    AddressItemPreview(
                        selectedAddresses = state.selectedAddresses,
                        account = account,
                    )
                    if (index != state.solanaAccounts.lastIndex) {
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
private fun ListHeaderContainerPreview(state: SolanaAccountsViewModel.ViewState.Content) {
    val notImportedCount = state.solanaAccounts.count { !it.isImported }
    val allSelected = state.selectedAddresses.size == notImportedCount && notImportedCount > 0
    val isSelectAllEnabled = notImportedCount > 0

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        AlgoKitTitleText(
            text = "${state.solanaAccounts.size} ${if (state.solanaAccounts.size == 1) "address" else "addresses"}",
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
    account: SolanaAccountsViewModel.SolanaAccountItem,
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
                account.derivationPath.let { name ->
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
