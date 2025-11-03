package com.michaeltchuang.walletsdk.ui.onboarding.screens

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.account_address_or_short_name
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.create_a_watch
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.create_a_watch_account
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_qr_scan
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
import androidx.compose.foundation.text.BasicTextField
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
import com.michaeltchuang.walletsdk.ui.onboarding.viewmodels.CreateWatchAccountViewModel
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.koin.compose.viewmodel.koinViewModel

private const val TAG = "CreateWatchAccountScreen"

@Composable
fun CreateWatchAccountScreen(
    navController: NavController = rememberNavController(),
    showSnackBar: (String) -> Unit = {},
    scannedAddress: String? = null
) {
    val viewModel: CreateWatchAccountViewModel = koinViewModel()
    val viewState by viewModel.state.collectAsState()
    val webViewController by rememberWebViewController()

    WebViewPlatform(webViewController = webViewController)

    scannedAddress?.let { address ->
        viewModel.onAddressChanged(address)
    }

    LaunchedEffect(Unit) {
        viewModel.viewEvent.collect { event ->
            when (event) {
                is CreateWatchAccountViewModel.ViewEvent.WatchAccountCreated -> {
                    navController.navigate(AlgoKitScreens.CREATE_ACCOUNT_NAME.name)
                }

                is CreateWatchAccountViewModel.ViewEvent.Error -> {
                    Log.e(TAG, event.message)
                    showSnackBar(event.message)
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
                    webViewController.open(WalletSdkConstants.SUPPORT_URL)
                }
            )
        }
    }
}

@Composable
private fun LoadingState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(color = AlgoKitTheme.colors.linkPrimary)
    }
}

@Composable
private fun ScreenContent(
    navController: NavController,
    viewState: CreateWatchAccountViewModel.ViewState.Content,
    onAddressChanged: (String) -> Unit,
    onCreateWatchAccount: () -> Unit,
    onInfoClick: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(color = AlgoKitTheme.colors.background)
            .padding(16.dp),
    ) {
        AlgoKitTopBar(
            onClick = { navController.popBackStack() },
            showInfoIcon = true,
            onInfoClick = onInfoClick
        )

        // Main Content
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = 24.dp),
        ) {
            Text(
                style = typography.title.regular.sansBold,
                color = AlgoKitTheme.colors.textMain,
                text = stringResource(Res.string.create_a_watch_account),
                fontWeight = FontWeight.Bold,
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                style = typography.body.regular.sansMedium,
                color = AlgoKitTheme.colors.textGray,
                text = stringResource(Res.string.you_are_creating_a_read_only),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                style = typography.body.regular.sansMedium,
                color = AlgoKitTheme.colors.textGray,
                text = stringResource(Res.string.monitor_activity_of),
            )

            Spacer(modifier = Modifier.height(40.dp))

            // Address Input Field
            WatchAccountBasicTextField(
                value = viewState.address,
                onValueChange = onAddressChanged,
                onClearClick = { onAddressChanged("") },
                hint = stringResource(Res.string.account_address_or_short_name),
                onQRScanClick = { navController.navigate("${AlgoKitScreens.QR_CODE_SCANNER_SCREEN.name}?isForWatchAccount=true") }
            )

            Spacer(modifier = Modifier.weight(1f))
        }

        Column {

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
                    text = stringResource(Res.string.monitor_activity_of),
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            // Create Watch Account Button
            AlgoKitPrimaryButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                onClick = onCreateWatchAccount,
                text = stringResource(Res.string.create_a_watch),
                state = if (viewState.isLoading) {
                    AlgoKitButtonState.PROGRESS
                } else if (viewState.isAddressValid) {
                    AlgoKitButtonState.ENABLED
                } else {
                    AlgoKitButtonState.DISABLED
                }
            )
        }


    }
}

@Composable
fun WatchAccountBasicTextField(
    value: String,
    onValueChange: (String) -> Unit,
    onClearClick: () -> Unit,
    hint: String = "",
    onQRScanClick: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
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
                            style = LocalTextStyle.current.copy(
                                color = AlgoKitTheme.colors.textGray,
                                fontSize = 16.sp,
                            )
                        )
                    }
                    innerTextField()
                }
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
            viewState = CreateWatchAccountViewModel.ViewState.Content(
                address = "", // Empty address to show the hint
                isAddressValid = false,
                isLoading = false
            ),
            onAddressChanged = {},
            onCreateWatchAccount = {},
            onInfoClick = {}
        )
    }
}