package com.michaeltchuang.walletsdk.ui.onboarding.screens

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.already_imported
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.continue_text
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_ledger_old_export
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_phone_new
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.searching_your_accounts
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.select_address_to_add
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.select_all
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.toUpperCase
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import com.michaeltchuang.walletsdk.core.account.domain.model.local.RegisteredHdKeyItem
import com.michaeltchuang.walletsdk.core.foundation.utils.formatAmount
import com.michaeltchuang.walletsdk.core.foundation.utils.toShortenedAddress
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.AlgoKitTopBar
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.button.AlgoKitButtonState
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.button.AlgoKitPrimaryButton
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.text.AlgoKitBodyText
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.text.AlgoKitHeadlineText
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.text.AlgoKitHighlightedGrayText
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.text.AlgoKitTitleText
import com.michaeltchuang.walletsdk.ui.base.navigation.AlgoKitScreens
import com.michaeltchuang.walletsdk.ui.onboarding.components.AnimationLoader
import com.michaeltchuang.walletsdk.ui.onboarding.viewmodels.RecoverRegisteredAccountsViewModel
import com.michaeltchuang.walletsdk.ui.settings.domain.localization.localizedStringResource
import io.github.alexzhirkevich.compottie.LottieCompositionSpec
import kotlinx.coroutines.flow.collectLatest
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun RecoverRegisteredAccountsScreen(
    viewModel: RecoverRegisteredAccountsViewModel = koinViewModel(),
    navController: NavController = rememberNavController(),
    showSnackBar: (message: String) -> Unit,
    allAccountImported: () -> Unit
) {
    val viewState by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.loadRegisteredAccounts()
    }

    LaunchedEffect(viewModel.viewEvent) {
        viewModel.viewEvent.collectLatest { event ->
            when (event) {
                is RecoverRegisteredAccountsViewModel.ViewEvent.NavigateToHome -> {
                    allAccountImported()
                }
                is RecoverRegisteredAccountsViewModel.ViewEvent.NavigateToAddressNaming -> {
                    // Handle navigation to address naming screen
                    navController.navigate("${AlgoKitScreens.ADDRESS_NAMING_SCREEN.name}?address=${event.address}")
                }
            }
        }
    }

    when (val currentState = viewState) {
        is RecoverRegisteredAccountsViewModel.ViewState.Idle -> Unit
        is RecoverRegisteredAccountsViewModel.ViewState.Loading -> LoadingStateContent()
        is RecoverRegisteredAccountsViewModel.ViewState.Content -> ContentStateContent(
            viewModel,
            currentState,
            onBackClick = { navController.popBackStack() }
        )
    }
}

@OptIn(ExperimentalResourceApi::class)
@Composable
private fun LoadingStateContent() {
    var animationData by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        animationData = Res.readBytes("files/loading_dots.json").decodeToString()
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AnimationLoader(
            modifier = Modifier.align(alignment = Alignment.Center),
            start = vectorResource(Res.drawable.ic_ledger_old_export),
            end = vectorResource(Res.drawable.ic_phone_new),
            lottie = LottieCompositionSpec.JsonString(animationData),
            description = stringResource(Res.string.searching_your_accounts)
        )
    }
}

@Composable
private fun ContentStateContent(
    viewModel: RecoverRegisteredAccountsViewModel,
    state: RecoverRegisteredAccountsViewModel.ViewState.Content,
    onBackClick: () -> Unit = {}
) {

    Box(
        modifier = Modifier
            .background(color = AlgoKitTheme.colors.background)
            .fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            AlgoKitTopBar(onClick = onBackClick)

            Spacer(modifier = Modifier.height(16.dp))

            TitleText()
            DescriptionText(state.registeredAccounts.size)
            ListHeaderContainer(
                state,
                onSelectAllAccounts = { viewModel.selectAllAccounts() },
                onUnselectAllAccounts = { viewModel.unselectAllAccounts() }
            )

            LazyColumn(modifier = Modifier.weight(1f)) {
                itemsIndexed(state.registeredAccounts) { index, account ->
                    AddressItem(
                        selectedAddresses = state.selectedAddresses,
                        account = account,
                        onCheckedChange = { isChecked ->
                            viewModel.toggleAccountSelection(account.address, isChecked)
                        }
                    )
                    if (index != state.registeredAccounts.lastIndex) {
                        HorizontalDivider(
                            color = AlgoKitTheme.colors.layerGrayLighter,
                            thickness = 1.dp
                        )
                    }
                }
            }

            val isPrimaryButtonEnabled = state.selectedAddresses.isNotEmpty() ||
                    (state.registeredAccounts.size == 1 && state.registeredAccounts[0].isImportedToDB)
            AlgoKitPrimaryButton(
                onClick = { viewModel.importSelectedAccounts() },
                modifier = Modifier.fillMaxWidth(),
                text = localizedStringResource(Res.string.continue_text),
                state = if (isPrimaryButtonEnabled) {
                    AlgoKitButtonState.ENABLED
                } else {
                    AlgoKitButtonState.DISABLED
                }
            )
        }

        if (state.type == RecoverRegisteredAccountsViewModel.ViewState.Content.ContentType.LoadingRekeyedAddresses) {
            // FetchingRekeyedAccountsLoadingDialog() - Add this if needed
        }
    }
}

@Composable
private fun TitleText() {
    AlgoKitHeadlineText(
        text = localizedStringResource(Res.string.select_address_to_add)
    )
}

@Composable
private fun DescriptionText(registeredAccountSize: Int) {
    AlgoKitBodyText(
        modifier = Modifier.padding(top = 10.dp),
        text = "Select the addresses you want to import ($registeredAccountSize ${if (registeredAccountSize == 1) "address" else "addresses"})"
    )
}

@Composable
private fun ListHeaderContainer(
    state: RecoverRegisteredAccountsViewModel.ViewState.Content,
    onSelectAllAccounts: () -> Unit,
    onUnselectAllAccounts: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 34.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AlgoKitTitleText(
            text = "${state.registeredAccounts.size} ${if (state.registeredAccounts.size == 1) "address" else "addresses"}",
            modifier = Modifier.weight(1f)
        )

        AlgoKitTitleText(
            text = localizedStringResource(Res.string.select_all),
            color = AlgoKitTheme.colors.textGray,
        )

        val currentToggleState = if (state.selectedAddresses.isEmpty()) {
            ToggleableState.Off
        } else if (state.selectedAddresses.size == state.registeredAddressesNotImported.size) {
            ToggleableState.On
        } else {
            ToggleableState.Indeterminate
        }

        val checkboxColors = CheckboxDefaults.colors(
            checkedColor = AlgoKitTheme.colors.buttonPrimaryBg,
            uncheckedColor = AlgoKitTheme.colors.layerGrayLighter
        )

        Checkbox(
            checked = currentToggleState == ToggleableState.On,
            onCheckedChange = { _ ->
                if (currentToggleState == ToggleableState.On) {
                    onUnselectAllAccounts()
                } else {
                    onSelectAllAccounts()
                }
            },
            colors = checkboxColors
        )
    }
}

@Suppress("MagicNumber")
@Composable
fun AddressItem(
    selectedAddresses: Set<String>,
    account: RegisteredHdKeyItem,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 76.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        AlgoKitTitleText(
            modifier = Modifier.fillMaxWidth(0.4f),
            text = account.address.toShortenedAddress().toUpperCase(Locale.current)
        )
        if (account.isImportedToDB) {
            AlgoKitHighlightedGrayText(
                modifier = Modifier.padding(start = 8.dp),
                text = localizedStringResource(Res.string.already_imported).toUpperCase(Locale.current)
            )
        } else {
            Row {
                Column {
                    AlgoKitTitleText(
                        text = (("\u00A6") + account.algoValue.toString().formatAmount())
                    )
                    //AlgoKitBodyText(text = account.formattedSelectedCurrencyValue)
                }
                val isChecked = selectedAddresses.contains(account.address)
                val checkboxColors = CheckboxDefaults.colors(
                    checkedColor = AlgoKitTheme.colors.buttonPrimaryBg,
                    uncheckedColor = AlgoKitTheme.colors.layerGrayLighter
                )

                Checkbox(
                    checked = isChecked,
                    onCheckedChange = { checked ->
                        onCheckedChange(checked)
                    },
                    colors = checkboxColors
                )
            }
        }
    }
}

@Preview
@Composable
private fun RecoverRegisteredAccountsScreenPreview() {
    val fakeViewState = RecoverRegisteredAccountsViewModel.ViewState.Content(
        registeredAccounts = listOf(
            RegisteredHdKeyItem(
                address = "IYRGNKJDFK3LFJD4K8GFJ4K3BFVKMY3E",
                algoValue = BigDecimal.fromInt(100),
                formattedSelectedCurrencyValue = "$0.10",
                accountExists = true,
                isImportedToDB = false,
                account = 0,
                change = 0,
                keyIndex = 0
            ),
            RegisteredHdKeyItem(
                address = "B7DFJHGK8LMNPQR9STUVWXYZ123456789",
                algoValue = BigDecimal.fromInt(250),
                formattedSelectedCurrencyValue = "$0.25",
                accountExists = true,
                isImportedToDB = true,
                account = 1,
                change = 0,
                keyIndex = 1
            )
        ),
        selectedAddresses = setOf("IYRGNKJDFK3LFJD4K8GFJ4K3BFVKMY3E"),
        registeredAddressesNotImported = setOf("IYRGNKJDFK3LFJD4K8GFJ4K3BFVKMY3E")
    )

    AlgoKitTheme {
        ContentStatePreview(fakeViewState)
    }
}

@Composable
private fun ContentStatePreview(
    state: RecoverRegisteredAccountsViewModel.ViewState.Content,
) {
    Scaffold { innerPadding ->
        Box(
            modifier = Modifier
                .background(color = AlgoKitTheme.colors.background)
                .fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(start = 24.dp, end = 24.dp)
            ) {
                AlgoKitTopBar(onClick = { })

                Spacer(modifier = Modifier.height(24.dp))

                TitleText()
                DescriptionText(state.registeredAccounts.size)
                ListHeaderContainerPreview(state)

                LazyColumn(modifier = Modifier.weight(1f)) {
                    itemsIndexed(state.registeredAccounts) { index, account ->
                        AddressItem(
                            selectedAddresses = state.selectedAddresses,
                            account = account,
                            onCheckedChange = { }
                        )
                        if (index != state.registeredAccounts.lastIndex) {
                            HorizontalDivider(
                                color = AlgoKitTheme.colors.layerGrayLighter,
                                thickness = 1.dp
                            )
                        }
                    }
                }

                AlgoKitPrimaryButton(
                    onClick = { },
                    modifier = Modifier.fillMaxWidth(),
                    text = localizedStringResource(Res.string.continue_text),
                    state = AlgoKitButtonState.ENABLED
                )
            }
        }
    }
}

@Composable
private fun ListHeaderContainerPreview(
    state: RecoverRegisteredAccountsViewModel.ViewState.Content,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 34.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AlgoKitTitleText(
            text = "${state.registeredAccounts.size} ${if (state.registeredAccounts.size == 1) "address" else "addresses"}",
            modifier = Modifier.weight(1f)
        )

        AlgoKitTitleText(
            text = localizedStringResource(Res.string.select_all),
            color = AlgoKitTheme.colors.textGray,
        )

        val currentToggleState = if (state.selectedAddresses.isEmpty()) {
            ToggleableState.Off
        } else if (state.selectedAddresses.size == state.registeredAddressesNotImported.size) {
            ToggleableState.On
        } else {
            ToggleableState.Indeterminate
        }

        val checkboxColors = CheckboxDefaults.colors(
            checkedColor = AlgoKitTheme.colors.buttonPrimaryBg,
            uncheckedColor = AlgoKitTheme.colors.layerGrayLighter
        )

        Checkbox(
            checked = currentToggleState == ToggleableState.On,
            onCheckedChange = { },
            colors = checkboxColors
        )
    }
}