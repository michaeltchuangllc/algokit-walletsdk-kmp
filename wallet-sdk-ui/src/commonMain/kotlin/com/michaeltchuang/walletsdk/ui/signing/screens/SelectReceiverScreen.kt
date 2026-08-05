package com.michaeltchuang.walletsdk.ui.signing.screens

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.account_type_algo25
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.account_type_falcon24
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.account_type_hd
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.account_type_ledger
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.account_type_seedvault
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.account_type_watch
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_clipboard
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_hd_wallet
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_wallet
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.my_accounts
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.next
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.paste_from_clipboard
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.search_or_enter_address
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.select_the_receiver_account
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.michaeltchuang.walletsdk.core.account.domain.model.core.AccountRegistrationType
import com.michaeltchuang.walletsdk.core.account.domain.model.custom.AccountLite
import com.michaeltchuang.walletsdk.core.algosdk.isValidAlgorandAddress
import com.michaeltchuang.walletsdk.core.foundation.utils.toShortenedAddress
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme.typography
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.AlgoKitTopBar
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.button.AlgoKitButtonState
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.button.AlgoKitPrimaryButton
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.icon.AlgoKitIcon
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.icon.AlgoKitIconRoundShape
import com.michaeltchuang.walletsdk.ui.base.navigation.AlgoKitScreens
import com.michaeltchuang.walletsdk.ui.settings.domain.localization.localizedStringResource
import com.michaeltchuang.walletsdk.ui.signing.viewmodels.SelectReceiverViewModel
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.vectorResource
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SelectReceiverScreen(
    navController: NavController,
    amount: String,
    note: String,
    senderAddress: String,
    assetId: Long = -7L,
) {
    val viewModel: SelectReceiverViewModel = koinViewModel()
    val viewState by viewModel.state.collectAsStateWithLifecycle()
    val events = viewModel.viewEvent.collectAsStateWithLifecycle(initialValue = null)
    val clipboardManager = LocalClipboardManager.current

    LaunchedEffect(senderAddress) {
        viewModel.setup(senderAddress = senderAddress)
        // Get clipboard text if available
        clipboardManager.getText()?.text?.let { clipboardText ->
            viewModel.setClipboardText(clipboardText)
        }
    }

    LaunchedEffect(events.value) {
        events.value?.let { event ->
            when (event) {
                is SelectReceiverViewModel.ViewEvent.NavigateToAssetTransfer -> {
                    navController.navigate(
                        "${AlgoKitScreens.ASSET_TRANSFER_SCREEN.name}?sender=${event.senderAddress}&receiver=${event.receiverAddress}&assetId=$assetId&amount=$amount&note=$note",
                    )
                }
            }
        }
    }

    ScreenContent(
        navController = navController,
        viewState = viewState,
        onSearchTextChange = { viewModel.setSearchText(it) },
        onAccountSelected = { viewModel.onAccountSelected(it) },
        onClipboardTapped = { viewModel.onClipboardAddressTapped(it) },
        onNextPressed = { viewModel.onNextPressed() },
    )
}

@Composable
 fun ScreenContent(
    navController: NavController,
    viewState: SelectReceiverViewModel.ViewState,
    onSearchTextChange: (String) -> Unit = {},
    onAccountSelected: (String) -> Unit = {},
    onClipboardTapped: (String) -> Unit = {},
    onNextPressed: () -> Unit = {},
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(AlgoKitTheme.colors.background),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(16.dp),
        ) {
            // Top Bar
            AlgoKitTopBar(
                title = localizedStringResource(Res.string.select_the_receiver_account),
                onClick = { navController.popBackStack() },
            )

            Spacer(modifier = Modifier.height(16.dp))

            when (val state = viewState) {
                is SelectReceiverViewModel.ViewState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = AlgoKitTheme.colors.textMain)
                    }
                }

                is SelectReceiverViewModel.ViewState.Content -> {
                    SelectReceiverContent(
                        state = state,
                        onSearchTextChange = onSearchTextChange,
                        onAccountSelected = onAccountSelected,
                        onClipboardTapped = onClipboardTapped,
                        onNextPressed = onNextPressed,
                    )
                }

                is SelectReceiverViewModel.ViewState.Error -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = state.message,
                            color = AlgoKitTheme.colors.negative,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectReceiverContent(
    state: SelectReceiverViewModel.ViewState.Content,
    onSearchTextChange: (String) -> Unit,
    onAccountSelected: (String) -> Unit,
    onClipboardTapped: (String) -> Unit,
    onNextPressed: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Search Field
        SearchField(
            searchText = state.searchText,
            onSearchTextChange = onSearchTextChange,
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Clipboard Section
        if (state.clipboardText.isNotEmpty()) {
            ClipboardSection(
                clipboardText = state.clipboardText,
                onClipboardTapped = onClipboardTapped,
            )

            Spacer(modifier = Modifier.height(24.dp))
        }

        // Account Section
        if (state.accounts.isNotEmpty()) {
            AccountSection(
                accounts = state.accounts,
                onAccountSelected = onAccountSelected,
            )
        } else if (state.searchText.isNotEmpty()) {
            // Show "No accounts found" message when searching
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No accounts found matching \"${state.searchText}\"",
                    style = typography.body.regular.sansMedium,
                    color = AlgoKitTheme.colors.textGray,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            // Show empty state when no accounts available
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No other accounts available",
                    style = typography.body.regular.sansMedium,
                    color = AlgoKitTheme.colors.textGray,
                    textAlign = TextAlign.Center,
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        AlgoKitPrimaryButton(
            onClick = onNextPressed,
            text = localizedStringResource(Res.string.next),
            modifier = Modifier.fillMaxWidth(),
            state =
                if (state.searchText.isNotEmpty() && isValidAlgorandAddress(state.searchText)) {
                    AlgoKitButtonState.ENABLED
                } else {
                    AlgoKitButtonState.DISABLED
                },
        )
    }
}

@Composable
private fun SearchField(
    searchText: String,
    onSearchTextChange: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = AlgoKitTheme.colors.layerGray,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Search",
                tint = AlgoKitTheme.colors.textGray,
                modifier = Modifier.size(20.dp),
            )

            BasicTextField(
                value = searchText,
                onValueChange = onSearchTextChange,
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                singleLine = true,
                textStyle =
                    LocalTextStyle.current.copy(
                        color = AlgoKitTheme.colors.textMain,
                        fontSize = 16.sp,
                    ),
                decorationBox = { innerTextField ->
                    if (searchText.isEmpty()) {
                        Text(
                            text = localizedStringResource(Res.string.search_or_enter_address),
                            style =
                                LocalTextStyle.current.copy(
                                    color = AlgoKitTheme.colors.textGray,
                                    fontSize = 12.sp,
                                ),
                        )
                    }
                    innerTextField()
                },
            )

            if (searchText.isNotEmpty()) {
                IconButton(
                    onClick = { onSearchTextChange("") },
                    modifier = Modifier.size(20.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Clear",
                        tint = AlgoKitTheme.colors.textGray,
                    )
                }
            }
        }
    }
}

@Composable
private fun ClipboardSection(
    clipboardText: String,
    onClipboardTapped: (String) -> Unit,
) {
    Column {
        Text(
            text = localizedStringResource(Res.string.paste_from_clipboard),
            style = typography.body.regular.sansMedium,
            color = AlgoKitTheme.colors.textGray,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { onClipboardTapped(clipboardText) },
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = clipboardText,
                    style = typography.body.regular.sansMedium,
                    color = AlgoKitTheme.colors.textMain,
                    modifier = Modifier.weight(1f),
                )

                AlgoKitIcon(
                    painter = painterResource(Res.drawable.ic_clipboard),
                    contentDescription = "Asset Icon",
                    modifier = Modifier,
                )
            }
        }
    }
}

@Composable
private fun AccountSection(
    accounts: List<AccountLite>,
    onAccountSelected: (String) -> Unit,
) {
    Column {
        Text(
            text = localizedStringResource(Res.string.my_accounts),
            style = typography.body.regular.sansMedium,
            color = AlgoKitTheme.colors.textGray,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(accounts) { account ->
                AccountItem(
                    account = account,
                    onAccountSelected = onAccountSelected,
                )
            }
        }
    }
}

@Composable
private fun AccountItem(
    account: AccountLite,
    onAccountSelected: (String) -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onAccountSelected(account.address) },
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AlgoKitIconRoundShape(
                imageVector = vectorResource(getWalletIcon(account.registrationType)),
                contentDescription = "Wallet Icon",
                backgroundColor = AlgoKitTheme.colors.wallet4,
            )

            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp),
            ) {
                Text(
                    text = account.customName.ifEmpty { account.address.toShortenedAddress() },
                    style = typography.body.large.sansMedium,
                    color = AlgoKitTheme.colors.textMain,
                )
                Text(
                    text = localizedStringResource(getAccountTypeResource(account.registrationType)),
                    style = typography.caption.mono,
                    color = AlgoKitTheme.colors.textGray,
                )
            }
        }
    }
}

private fun getWalletIcon(registrationType: AccountRegistrationType): DrawableResource =
    when (registrationType) {
        is AccountRegistrationType.HdKey -> Res.drawable.ic_hd_wallet
        else -> Res.drawable.ic_wallet
    }

fun getAccountTypeResource(localAccount: AccountRegistrationType): StringResource =
    when (localAccount) {
        is AccountRegistrationType.HdKey -> Res.string.account_type_hd
        is AccountRegistrationType.Algo25 -> Res.string.account_type_algo25
        is AccountRegistrationType.Falcon24,
        is AccountRegistrationType.Falcon25,
        -> Res.string.account_type_falcon24
        is AccountRegistrationType.NoAuth -> Res.string.account_type_watch
        is AccountRegistrationType.LedgerBle -> Res.string.account_type_ledger
        is AccountRegistrationType.SeedVault -> Res.string.account_type_seedvault
    }

@Preview
@Composable
fun PreviewSelectReceiverScreen() {
    AlgoKitTheme {
        SelectReceiverScreen(
            navController = rememberNavController(),
            amount = "1.000 ALGO",
            note = "",
            senderAddress = "HVTAJEVD6WVPY53MUZGPRJ446WW5C3SUSKNSQ3UCZH2R4XWQZPXE72MQ",
            assetId = -7L,
        )
    }
}
