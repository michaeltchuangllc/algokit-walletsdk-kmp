package com.michaeltchuang.walletsdk.ui.signing.screens

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.account
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.adding_asset
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.approve
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.before_interacting_with
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.close
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.copy_id
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.fee
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_asa_trusted
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_wallet
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.michaeltchuang.walletsdk.core.foundation.utils.toAlgoCurrency
import com.michaeltchuang.walletsdk.core.foundation.utils.toShortenedAddress
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme.typography
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.AlgoKitTopBar
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.button.AlgoKitPrimaryButton
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.button.AlgoKitSecondaryButton
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.icon.AlgoKitIconRoundShape
import com.michaeltchuang.walletsdk.ui.base.navigation.AlgoKitScreens
import com.michaeltchuang.walletsdk.ui.settings.domain.localization.localizedStringResource
import com.michaeltchuang.walletsdk.ui.signing.viewmodels.AddAssetViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun AddAssetScreen(
    navController: NavController,
    assetId: Long = 0L,
    accountAddress: String = "",
) {
    val viewModel: AddAssetViewModel = koinViewModel()
    val lifecycleOwner = LocalLifecycleOwner.current
    val viewState by viewModel.state.collectAsState()
    val events = viewModel.viewEvent.collectAsStateWithLifecycle(initialValue = null)
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Reset ViewModel state when entering the screen
    LaunchedEffect(Unit) {
        viewModel.reset()
        viewModel.setup(lifecycleOwner.lifecycle)
    }

    LaunchedEffect(events.value) {
        events.value?.let { event ->
            when (event) {
                is AddAssetViewModel.ViewEvent.ShowError -> {
                    scope.launch {
                        snackbarHostState.showSnackbar(event.message)
                    }
                }

                is AddAssetViewModel.ViewEvent.AssetOptInSuccess -> {
                    navController.navigate(
                        AlgoKitScreens.TRANSACTION_SUCCESS_SCREEN.name + "/?transactionId=${event.transactionId}",
                    ) {
                        popUpTo(AlgoKitScreens.ADD_ASSET_SCREEN.name) {
                            inclusive = true
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(assetId, accountAddress) {
        viewModel.fetchAssetDetail(assetId.toString())
        if (accountAddress.isNotEmpty()) {
            viewModel.setAccountAddress(accountAddress)
        }
    }

    ScreenContent(
        navController = navController,
        viewState = viewState,
        snackbarHostState = snackbarHostState,
        onApproveClick = { viewModel.approveAssetOptIn() },
        onCloseClick = { navController.popBackStack() },
        onCopyIdClick = { viewModel.copyAssetId() },
    )
}

@Composable
internal fun ScreenContent(
    navController: NavController,
    viewState: AddAssetViewModel.ViewState,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onApproveClick: () -> Unit = {},
    onCloseClick: () -> Unit = {},
    onCopyIdClick: () -> Unit = {},
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (val state = viewState) {
            is AddAssetViewModel.ViewState.Loading -> {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(color = AlgoKitTheme.colors.background),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Loading...", color = AlgoKitTheme.colors.textMain)
                }
            }

            is AddAssetViewModel.ViewState.Confirming -> {
                // Show pending transaction loader if needed
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(color = AlgoKitTheme.colors.background),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Confirming...", color = AlgoKitTheme.colors.textMain)
                }
            }

            is AddAssetViewModel.ViewState.Content -> {
                AddAssetContent(
                    state = state,
                    navController = navController,
                    onApproveClick = onApproveClick,
                    onCloseClick = onCloseClick,
                    onCopyIdClick = onCopyIdClick,
                )
            }

            is AddAssetViewModel.ViewState.Error -> {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(color = AlgoKitTheme.colors.background),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(state.message, color = AlgoKitTheme.colors.textMain)
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
        )
    }
}

@Composable
fun AddAssetContent(
    state: AddAssetViewModel.ViewState.Content,
    navController: NavController,
    onApproveClick: () -> Unit,
    onCloseClick: () -> Unit,
    onCopyIdClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .background(color = AlgoKitTheme.colors.background)
                .fillMaxSize()
                .padding(16.dp),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
        ) {
            AlgoKitTopBar(
                title = localizedStringResource(Res.string.adding_asset),
                onClick = { navController.popBackStack() },
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Asset Name with verified badge
            AssetNameHeader(
                assetName = state.assetName,
                isVerified = state.isVerified,
            )

            AddAssetDivider()
            // Asset ID with Copy button
            AssetIdSection(
                assetId = state.assetId,
                onCopyIdClick = onCopyIdClick,
            )

            AddAssetDivider()

            // Account
            AccountSection(
                accountAddress = state.accountAddress,
            )

            AddAssetDivider()

            // Transaction Fee
            FeeSection(
                fee = state.fee,
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Informational text
            Text(
                text = localizedStringResource(Res.string.before_interacting_with),
                style = typography.body.regular.sansMedium,
                color = AlgoKitTheme.colors.textGray,
                modifier = Modifier.padding(horizontal = 8.dp),
            )

            Spacer(modifier = Modifier.weight(1f))

            Spacer(modifier = Modifier.height(16.dp))

            // Buttons
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AlgoKitPrimaryButton(
                    onClick = onApproveClick,
                    text = localizedStringResource(Res.string.approve),
                    modifier = Modifier.fillMaxWidth(),
                )

                AlgoKitSecondaryButton(
                    onClick = onCloseClick,
                    text = localizedStringResource(Res.string.close),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun AssetNameHeader(
    assetName: String,
    isVerified: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 8.dp),
    ) {
        Text(
            text = assetName,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = AlgoKitTheme.colors.textMain,
        )
        if (isVerified) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                painter = painterResource(Res.drawable.ic_asa_trusted),
                contentDescription = "Verified",
                tint = AlgoKitTheme.colors.verifiedIconInline,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
fun AssetIdSection(
    assetId: String,
    onCopyIdClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = assetId,
            style = typography.body.regular.sansMedium,
            color = AlgoKitTheme.colors.textGray,
        )

        Card(
            onClick = onCopyIdClick,
            shape = RoundedCornerShape(20.dp),
            colors =
                CardDefaults.cardColors(
                    containerColor = AlgoKitTheme.colors.layerGrayLighter,
                ),
        ) {
            Text(
                text = localizedStringResource(Res.string.copy_id),
                style = typography.body.regular.sansMedium,
                color = AlgoKitTheme.colors.textMain,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
fun AccountSection(accountAddress: String) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            modifier = Modifier.fillMaxWidth(0.25f),
            text = localizedStringResource(Res.string.account),
            color = AlgoKitTheme.colors.textGray,
            style = typography.body.regular.sansMedium,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AlgoKitIconRoundShape(
                imageVector =
                    org.jetbrains.compose.resources
                        .vectorResource(Res.drawable.ic_wallet),
                contentDescription = "Wallet Icon",
                backgroundColor = AlgoKitTheme.colors.wallet4,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = accountAddress.toShortenedAddress(),
                color = AlgoKitTheme.colors.textMain,
                style = typography.body.regular.sansMedium,
            )
        }
    }
}

@Composable
fun FeeSection(fee: String) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            modifier = Modifier.fillMaxWidth(0.25f),
            text = localizedStringResource(Res.string.fee),
            color = AlgoKitTheme.colors.textGray,
            style = typography.body.regular.sansMedium,
        )
        Text(
            text = fee.toAlgoCurrency(),
            color = AlgoKitTheme.colors.textMain,
            style = typography.body.regular.sansMedium,
        )
    }
}

@Composable
fun AddAssetDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 16.dp, horizontal = 8.dp),
        thickness = androidx.compose.material3.DividerDefaults.Thickness,
        color = AlgoKitTheme.colors.layerGray,
    )
}

@Preview
@Composable
fun AddAssetScreenPreview() {
    AlgoKitTheme {
        val previewState =
            AddAssetViewModel.ViewState.Content(
                assetId = "10458941",
                assetName = "USDC",
                accountAddress = "N44MXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX7CWM",
                fee = "0.001",
                isVerified = true,
            )
        Box(
            modifier = Modifier.fillMaxSize(),
        ) {
            AddAssetContent(
                state = previewState,
                navController = androidx.navigation.compose.rememberNavController(),
                onApproveClick = {},
                onCloseClick = {},
                onCopyIdClick = {},
            )
        }
    }
}
