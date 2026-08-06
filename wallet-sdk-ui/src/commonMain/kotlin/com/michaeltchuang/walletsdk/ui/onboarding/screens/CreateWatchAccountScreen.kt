package com.michaeltchuang.walletsdk.ui.onboarding.screens

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.account_address_or_short_name
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.create_a_watch
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.create_a_watch_account
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.error_account_already_exists
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.error_failed_to_validate_watch_account
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.error_invalid_algorand_address
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_qr_scan
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.if_you_do_not
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.monitor_activity_of
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.you_are_creating_a_read_only
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.final_class.webview_multiplatform_mobile.webview.WebViewPlatform
import com.final_class.webview_multiplatform_mobile.webview.controller.rememberWebViewController
import com.michaeltchuang.walletsdk.core.foundation.utils.Log
import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme.typography
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.AlgoKitTopBar
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.button.AlgoKitButtonState
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.button.AlgoKitPrimaryButton
import com.michaeltchuang.walletsdk.ui.base.navigation.AlgoKitScreens
import com.michaeltchuang.walletsdk.ui.base.webview.openUrl
import com.michaeltchuang.walletsdk.ui.onboarding.viewmodels.CreateWatchAccountViewModel
import com.michaeltchuang.walletsdk.ui.settings.domain.localization.localizedStringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.koin.compose.viewmodel.koinViewModel

private const val TAG = "CreateWatchAccountScreen"

@Composable
fun CreateWatchAccountScreen(
    navController: NavController = rememberNavController(),
    showSnackBar: (String) -> Unit = {},
    scannedAddress: String? = null,
) {
    val viewModel: CreateWatchAccountViewModel = koinViewModel()
    val viewState by viewModel.state.collectAsState()
    val webViewController by rememberWebViewController()

    WebViewPlatform(webViewController = webViewController)

    scannedAddress?.let { address ->
        viewModel.onAddressChanged(address)
    }

    // Create localized error messages map
    val errorMessages =
        mapOf(
            CreateWatchAccountViewModel.ErrorType.InvalidAddress to localizedStringResource(Res.string.error_invalid_algorand_address),
            CreateWatchAccountViewModel.ErrorType.AccountAlreadyExists to localizedStringResource(Res.string.error_account_already_exists),
            CreateWatchAccountViewModel.ErrorType.ValidationFailed to
                localizedStringResource(Res.string.error_failed_to_validate_watch_account),
        )

    LaunchedEffect(Unit) {
        viewModel.viewEvent.collect { event ->
            when (event) {
                is CreateWatchAccountViewModel.ViewEvent.WatchAccountCreated -> {
                    navController.navigate(AlgoKitScreens.CREATE_ACCOUNT_NAME.name)
                }

                is CreateWatchAccountViewModel.ViewEvent.Error -> {
                    val errorMessage = errorMessages[event.errorType] ?: "An error occurred"
                    Log.e(TAG, errorMessage)
                    showSnackBar(errorMessage)
                }
            }
        }
    }

    when (viewState) {
        is CreateWatchAccountViewModel.ViewState.Idle -> {
            // Initialize with content state
            LaunchedEffect(Unit) {
                viewModel.onAddressChanged("")
            }
        }

        is CreateWatchAccountViewModel.ViewState.Loading -> {
            LoadingState()
        }

        is CreateWatchAccountViewModel.ViewState.Content -> {
            ScreenContent(
                navController = navController,
                viewState = viewState as CreateWatchAccountViewModel.ViewState.Content,
                onAddressChanged = viewModel::onAddressChanged,
                onCreateWatchAccount = viewModel::createWatchAccount,
                onInfoClick = {
                    webViewController.openUrl(WalletSdkConstants.WATCH_ACCOUNT_LEARN_MORE)
                },
            )
        }
    }
}

@Composable
private fun LoadingState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = AlgoKitTheme.colors.linkPrimary)
    }
}

@Composable
fun ScreenContent(
    navController: NavController,
    viewState: CreateWatchAccountViewModel.ViewState.Content,
    onAddressChanged: (String) -> Unit,
    onCreateWatchAccount: () -> Unit,
    onInfoClick: () -> Unit = {},
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(color = AlgoKitTheme.colors.background)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
    ) {
        AlgoKitTopBar(
            onClick = { navController.popBackStack() },
            showInfoIcon = true,
            onInfoClick = onInfoClick,
        )

        Text(
            style = typography.title.regular.sansBold,
            color = AlgoKitTheme.colors.textMain,
            text = localizedStringResource(Res.string.create_a_watch_account),
            fontWeight = FontWeight.Bold,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            style = typography.body.regular.sansMedium,
            color = AlgoKitTheme.colors.textGray,
            text = localizedStringResource(Res.string.you_are_creating_a_read_only),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            style = typography.body.regular.sansMedium,
            color = AlgoKitTheme.colors.textGray,
            text = localizedStringResource(Res.string.monitor_activity_of),
        )

        Spacer(modifier = Modifier.height(40.dp))

        // Address Input Field
        WatchAccountBasicTextField(
            value = viewState.address,
            onValueChange = onAddressChanged,
            onClearClick = { onAddressChanged("") },
            hint = localizedStringResource(Res.string.account_address_or_short_name),
            onQRScanClick = { navController.navigate("${AlgoKitScreens.QR_CODE_SCANNER_SCREEN.name}?isForWatchAccount=true") },
        )

        Spacer(modifier = Modifier.height(50.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                tint = AlgoKitTheme.colors.negative,
                imageVector = Icons.Default.Info,
                contentDescription = "Info",
            )
            Spacer(modifier = Modifier.width(8.dp))

            Text(
                style = typography.body.regular.sansMedium,
                color = AlgoKitTheme.colors.negative,
                text = localizedStringResource(Res.string.if_you_do_not),
            )
        }

        Spacer(modifier = Modifier.height(50.dp))
        // Create Watch Account Button
        AlgoKitPrimaryButton(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
            onClick = onCreateWatchAccount,
            text = localizedStringResource(Res.string.create_a_watch),
            state =
                if (viewState.isLoading) {
                    AlgoKitButtonState.PROGRESS
                } else if (viewState.isAddressValid) {
                    AlgoKitButtonState.ENABLED
                } else {
                    AlgoKitButtonState.DISABLED
                },
        )
    }
}

@Composable
fun WatchAccountBasicTextField(
    value: String,
    onValueChange: (String) -> Unit,
    onClearClick: () -> Unit,
    hint: String = "",
    onQRScanClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            BasicTextField(
                value = value,
                cursorBrush = SolidColor(AlgoKitTheme.colors.textMain),
                onValueChange = onValueChange,
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(vertical = 8.dp),
                singleLine = true,
                textStyle =
                    LocalTextStyle.current.copy(
                        color = AlgoKitTheme.colors.textMain,
                        fontSize = 16.sp,
                    ),
                decorationBox = { innerTextField ->
                    if (value.isEmpty()) {
                        Text(
                            text = hint,
                            style =
                                LocalTextStyle.current.copy(
                                    color = AlgoKitTheme.colors.textGray,
                                    fontSize = 16.sp,
                                ),
                        )
                    }
                    innerTextField()
                },
            )
            IconButton(modifier = Modifier.offset(x = (10).dp), onClick = onQRScanClick) {
                Icon(
                    painter = painterResource(resource = Res.drawable.ic_qr_scan),
                    contentDescription = "QR Scan",
                    tint = Color.Gray,
                )
            }
        }

        // Bottom Line
        HorizontalDivider(
            thickness = 1.dp,
            color = Color.Gray,
        )
    }
}

@Preview
@Composable
private fun CreateWatchAccountScreenPreview() {
    AlgoKitTheme {
        ScreenContent(
            navController = rememberNavController(),
            viewState =
                CreateWatchAccountViewModel.ViewState.Content(
                    address = "", // Empty address to show the hint
                    isAddressValid = false,
                    isLoading = false,
                ),
            onAddressChanged = {},
            onCreateWatchAccount = {},
            onInfoClick = {},
        )
    }
}
