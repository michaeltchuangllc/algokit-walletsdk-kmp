package com.michaeltchuang.walletsdk.ui.liquidAuth.screens

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_hd_wallet
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_wallet
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.select_account
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.michaeltchuang.walletsdk.core.account.domain.model.core.AccountRegistrationType
import com.michaeltchuang.walletsdk.core.account.domain.model.custom.AccountLite
import com.michaeltchuang.walletsdk.core.foundation.utils.formatAmount
import com.michaeltchuang.walletsdk.core.foundation.utils.toShortenedAddress
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme.typography
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.AlgoKitTopBar
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.button.AlgoKitButtonState
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.button.AlgoKitPrimaryButton
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.icon.AlgoKitIconRoundShape
import com.michaeltchuang.walletsdk.ui.liquidAuth.connect
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.LiquidAuthViewModel
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun LiquidAuthScreen(
    navController: NavController,
    uri: String?,
) {
    val viewModel: LiquidAuthViewModel = koinViewModel()
    val viewState = viewModel.state.collectAsStateWithLifecycle().value
    val onConnect = remember { mutableStateOf(false) }
    var selectedAccount by remember { mutableStateOf("") }

    LaunchedEffect(uri) {
        // Decode the URI if it was encoded during navigation
        val decodedUri =
            uri?.let { encoded ->
                encoded
                    .replace("%23", "#")
                    .replace("%3D", "=")
                    .replace("%26", "&")
                    .replace("%3F", "?")
                    .replace("%25", "%") // Must be last to avoid double-decoding!
            }

        println("📥 LiquidAuthScreen received URI:")
        println("   Encoded: $uri")
        println("   Decoded: $decodedUri")

        viewModel.initialize(decodedUri)
    }

    LaunchedEffect(Unit) {
        viewModel.viewEvent.collect { event ->
            when (event) {
                is LiquidAuthViewModel.ViewEvent.AuthenticationSuccess -> {
                    navController.popBackStack()
                }

                is LiquidAuthViewModel.ViewEvent.ShowError -> {
                }
            }
        }
    }
    ScreenContentLiquidAuth(
        viewState,
        onAccountSelected = {
            onConnect.value = true
            selectedAccount = it
        },
        onBack = {
            navController.popBackStack()
        },
    )

    if (onConnect.value) {
        connect(viewModel.authMessage, selectedAccount)
        onConnect.value = false
    }
}

@Composable
private fun CenteredMessage(message: String) {
    CenteredContent {
        Text(
            text = message,
            style = AlgoKitTheme.typography.body.regular.sans,
            color = AlgoKitTheme.colors.textGray,
        )
    }
}

@Composable
private fun CenteredContent(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun ContentView(
    origin: String,
    requestId: String,
    rawUri: String,
    onConnect: () -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            InfoField(label = "Origin", value = origin)
            Spacer(modifier = Modifier.height(24.dp))
            InfoField(label = "Request ID", value = requestId)
            Spacer(modifier = Modifier.height(24.dp))
            InfoField(
                label = "Raw URI",
                value = rawUri,
                valueColor = AlgoKitTheme.colors.textGray,
            )
        }

        AlgoKitPrimaryButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = onConnect,
            text = "Connect",
            state = AlgoKitButtonState.ENABLED,
        )
    }
}

@Composable
private fun InfoField(
    label: String,
    value: String,
    valueColor: Color = AlgoKitTheme.colors.textMain,
) {
    Text(
        text = label,
        style = AlgoKitTheme.typography.body.regular.sansMedium,
        color = AlgoKitTheme.colors.textGray,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = value,
        style = AlgoKitTheme.typography.body.regular.sans,
        color = valueColor,
    )
}

@Composable
fun ScreenContentLiquidAuth(
    viewState: LiquidAuthViewModel.ViewState,
    onAccountSelected: (String) -> Unit,
    onBack: () -> Unit,
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
            AlgoKitTopBar(
                title = stringResource(Res.string.select_account),
                onClick = onBack,
            )

            Spacer(modifier = Modifier.height(16.dp))

            when (viewState) {
                is LiquidAuthViewModel.ViewState.Loading -> {
                    CenteredLoader()
                }

                is LiquidAuthViewModel.ViewState.Content -> {
                    val accounts =
                        viewState.accounts
                    if (accounts.isEmpty()) {
                        CenteredMessage("No accounts available")
                    } else {
                        AccountsList(
                            accounts = accounts,
                            onAccountItemClick = { address ->
                                val account = accounts.find { it.address == address }
                                if (account != null) {
                                    onAccountSelected(account.address)
                                }
                            },
                        )
                    }
                }

                is LiquidAuthViewModel.ViewState.Error -> {
                    CenteredMessage(
                        text = "Error: ${viewState.message}",
                        color = AlgoKitTheme.colors.negative,
                    )
                }

                LiquidAuthViewModel.ViewState.Idle -> {}
            }
        }
    }
}

@Composable
private fun CenteredLoader() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            color = AlgoKitTheme.colors.textMain,
        )
    }
}

@Composable
private fun CenteredMessage(
    text: String,
    color: Color = AlgoKitTheme.colors.textMain,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = color,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AccountsList(
    accounts: List<AccountLite>,
    onAccountItemClick: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(accounts) { account ->
            AccountItem(
                account = account,
                onAccountItemClick = onAccountItemClick,
            )
        }
    }
}

@Composable
private fun AccountItem(
    account: AccountLite,
    onAccountItemClick: (address: String) -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = {
                    onAccountItemClick(account.address)
                }),
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
                    text = getAccountTypeText(account.registrationType),
                    style = typography.footnote.mono,
                    color = AlgoKitTheme.colors.textGray,
                )
            }

            Column(
                horizontalAlignment = Alignment.End,
            ) {
                Text(
                    text = "\u00A6${account.balance?.formatAmount() ?: "0.00"}",
                    fontSize = 16.sp,
                    style = typography.footnote.sansMedium,
                    color = AlgoKitTheme.colors.textMain,
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

private fun getAccountTypeText(registrationType: AccountRegistrationType): String =
    when (registrationType) {
        is AccountRegistrationType.HdKey -> "HD Account"
        is AccountRegistrationType.Algo25 -> "Algo25"
        is AccountRegistrationType.Falcon24 -> "Falcon24"
        is AccountRegistrationType.NoAuth -> "Watch"
        is AccountRegistrationType.LedgerBle -> "Ledger"
    }

@Preview
@Composable
fun LiquidAuthScreenPreview() {
    val accounts =
        mutableListOf<AccountLite>().apply {
            add(
                AccountLite(
                    address = "address1",
                    customName = "Account 1",
                    registrationType = AccountRegistrationType.HdKey,
                    balance = "1",
                ),
            )
            add(
                AccountLite(
                    address = "address1",
                    customName = "Account 1",
                    registrationType = AccountRegistrationType.HdKey,
                    balance = "1",
                ),
            )
        }
    ScreenContentLiquidAuth(
        viewState = LiquidAuthViewModel.ViewState.Content(accounts),
        onAccountSelected = {
        },
        onBack = { },
    )
}
