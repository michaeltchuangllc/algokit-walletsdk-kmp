package com.michaeltchuang.walletsdk.ui.onboarding.screens

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.create_a_new_algorand_account_with
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.create_a_new_wallet
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.create_your_new_falcon24_account
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_plus
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_wallet
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.plus
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.select_falcon24_wallet
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.michaeltchuang.walletsdk.core.foundation.utils.Log
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.AlgoKitTopBar
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.button.AlgoKitSecondaryButton
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.icon.AlgoKitIcon
import com.michaeltchuang.walletsdk.ui.base.navigation.AlgoKitScreens
import com.michaeltchuang.walletsdk.ui.onboarding.viewmodels.Falcon24WalletSelectionViewModel
import com.michaeltchuang.walletsdk.ui.settings.screens.HdWalletSelectionScreenContent
import com.michaeltchuang.walletsdk.ui.settings.viewmodels.HDWalletSelectionViewModel
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.koin.compose.viewmodel.koinViewModel

private const val TAG = "Falcon24WalletSelectionScreen"

@Composable
fun Falcon24WalletSelectionScreen(
    viewModel: Falcon24WalletSelectionViewModel = koinViewModel(),
    navController: NavController,
) {
    val viewState by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        viewModel.viewEvent.collect {
            when (it) {
                is Falcon24WalletSelectionViewModel.ViewEvent.AccountCreated -> {
                    navController.navigate(AlgoKitScreens.CREATE_ACCOUNT_NAME.name)
                    Log.d(TAG, it.accountCreation.address)
                }

                is Falcon24WalletSelectionViewModel.ViewEvent.Error -> {
                    Log.d(TAG, it.message)
                }
            }
        }
    }

    Falcon24WalletSelectionScreenContent(
        viewState = viewState,
        navController = navController,
        createNewWalletClick = { viewModel.createFalcon24Account() },
        walletItemClick = {
            viewModel.createNewFalcon24Account(
                it.seedId,
            )
        },
    )
}

@Composable
fun Falcon24WalletSelectionScreenContent(
    viewState: Falcon24WalletSelectionViewModel.ViewState,
    navController: NavController,
    createNewWalletClick: () -> Unit = {},
    walletItemClick: (Falcon24WalletSelectionViewModel.WalletItemPreview) -> Unit = {},
) {
    Box(
        modifier =
            Modifier
                .background(color = AlgoKitTheme.colors.background)
                .fillMaxWidth()
                .fillMaxHeight(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxHeight(.9f)
                    .padding(top = 16.dp, start = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            AlgoKitTopBar(
                onClick = { navController.popBackStack() },
            )
            when (viewState) {
                is Falcon24WalletSelectionViewModel.ViewState.Content -> {
                    ContentState(
                        viewState.walletItemPreviews,
                        createNewWalletClick,
                        walletItemClick,
                    )
                }

                is Falcon24WalletSelectionViewModel.ViewState.Error -> {}
                is Falcon24WalletSelectionViewModel.ViewState.Idle -> {}
                is Falcon24WalletSelectionViewModel.ViewState.Loading -> {}
            }
        }
    }
}

@Suppress("LongMethod")
@Composable
private fun ContentState(
    walletItems: List<Falcon24WalletSelectionViewModel.WalletItemPreview>,
    createNewWalletClick: () -> Unit,
    walletItemClick: (Falcon24WalletSelectionViewModel.WalletItemPreview) -> Unit,
) {
    Box {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(top = 24.dp),
        ) {
            Text(
                style = AlgoKitTheme.typography.title.regular.sansMedium,
                text = stringResource(Res.string.select_falcon24_wallet),
                color = AlgoKitTheme.colors.textMain,
            )
            Text(
                style = AlgoKitTheme.typography.body.regular.sans,
                text = stringResource(Res.string.create_your_new_falcon24_account),
                color = AlgoKitTheme.colors.textGray,
                modifier = Modifier.padding(top = 12.dp),
            )
            LazyColumn(modifier = Modifier.padding(top = 24.dp, bottom = 50.dp)) {
                items(walletItems) { walletItemPreview ->
                    with(walletItemPreview) {
                        WalletItem(
                            modifier = Modifier,
                            walletName = walletItemPreview.name,
                            numberOfAccounts = walletItemPreview.numberOfAccounts,
                            icon = vectorResource(Res.drawable.ic_wallet),
                            iconContentDescription =
                                stringResource(
                                    Res.string.create_a_new_algorand_account_with,
                                ),
                            onClick = { walletItemClick(walletItemPreview) },
                        )
                    }
                }
            }
        }
        AlgoKitSecondaryButton(
            onClick = {
                createNewWalletClick()
            },
            text = stringResource(Res.string.create_a_new_wallet),
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            leftIcon = {
                AlgoKitIcon(
                    painter = painterResource(Res.drawable.ic_plus),
                    contentDescription = stringResource(Res.string.plus),
                    modifier = Modifier,
                )
            },
        )
    }
}

@Composable
fun WalletItem(
    modifier: Modifier = Modifier,
    walletName: String,
    numberOfAccounts: String,
    icon: ImageVector,
    iconContentDescription: String,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            modifier
                .clickable { onClick() }
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .padding(vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            modifier =
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(AlgoKitTheme.colors.layerGrayLighter)
                    .padding(8.dp),
            imageVector = icon,
            contentDescription = iconContentDescription,
            tint = AlgoKitTheme.colors.textMain,
        )
        Spacer(modifier = Modifier.width(24.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                style = AlgoKitTheme.typography.body.regular.sans,
                color = AlgoKitTheme.colors.textMain,
                text = walletName,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                style = AlgoKitTheme.typography.footnote.sans,
                color = AlgoKitTheme.colors.textGrayLighter,
                text = numberOfAccounts,
            )
        }
    }
}

@Preview
@Composable
fun HdWalletSelectionScreenContentPreview() {
    val fakeViewState = HDWalletSelectionViewModel.ViewState.Content()
    AlgoKitTheme {
        HdWalletSelectionScreenContent(
            viewState = fakeViewState,
            navController = rememberNavController(),
        )
    }
}
