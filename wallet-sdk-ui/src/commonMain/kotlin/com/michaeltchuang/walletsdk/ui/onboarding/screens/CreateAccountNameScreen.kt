package com.michaeltchuang.walletsdk.ui.onboarding.screens

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.finish_account_creation
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_wallet
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.name_your_account
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.michaeltchuang.walletsdk.core.foundation.utils.Log
import com.michaeltchuang.walletsdk.core.foundation.utils.manager.AccountCreationManager
import com.michaeltchuang.walletsdk.core.foundation.utils.toShortenedAddress
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme.typography
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.AlgoKitTopBar
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.button.AlgoKitPrimaryButton
import com.michaeltchuang.walletsdk.ui.onboarding.viewmodels.CreateAccountNameViewModel
import com.michaeltchuang.walletsdk.ui.settings.domain.localization.localizedStringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun CreateAccountNameScreen(
    navController: NavController,
    showSnackBar: (message: String) -> Unit,
    onFinish: () -> Unit,
) {
    val accountCreation = AccountCreationManager.getPendingAccountCreation()

    // Handle case where no account creation data is available
    if (accountCreation == null) {
        LaunchedEffect(Unit) {
            Log.e("CreateAccountNameScreen", "No account creation data found, navigating back")
            navController.popBackStack()
        }
        return
    }

    val viewModel: CreateAccountNameViewModel = koinViewModel()
    val viewState = viewModel.state.collectAsStateWithLifecycle().value
    val accountName = remember { mutableStateOf(accountCreation.address.toShortenedAddress()) }

    LaunchedEffect(accountCreation) {
        viewModel.fetchAccountDetails(accountCreation)
    }

    when (viewState) {
        is CreateAccountNameViewModel.ViewState.Content -> {
            ScreenContent(
                navController,
                accountName,
                seedId = viewState.walletId,
                onFinishClick = {
                    viewModel.addNewAccount(accountCreation, accountName.value)
                },
            )
        }

        is CreateAccountNameViewModel.ViewState.Idle -> {}
        is CreateAccountNameViewModel.ViewState.Loading -> {}
    }

    LaunchedEffect(Unit) {
        viewModel.viewEvent.collect { event ->
            when (event) {
                is CreateAccountNameViewModel.ViewEvent.FinishedAccountCreation -> {
                    onFinish()
                }

                is CreateAccountNameViewModel.ViewEvent.Error -> {
                    showSnackBar(event.message)
                    Log.e("CreateAccountNameScreen", "Error: ${event.message}")
                }
            }
        }
    }
}

@Composable
fun ScreenContent(
    navController: NavController,
    accountName: MutableState<String>,
    seedId: Int?,
    onFinishClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .background(color = AlgoKitTheme.colors.background)
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(16.dp),
    ) {
        AlgoKitTopBar(
            onClick = { navController.popBackStack() },
        )
        // Main Content
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(top = 64.dp),
        ) {
            Text(
                style = typography.title.regular.sansBold,
                color = AlgoKitTheme.colors.textMain,
                text = localizedStringResource(Res.string.name_your_account),
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                style = typography.body.regular.sansMedium,
                color = AlgoKitTheme.colors.textMain,
                text = "Name your account to easily identify it while using the AlgoKit Wallet. These names are stored locally, and can only be seen by you.",
            )

            Spacer(modifier = Modifier.height(24.dp))
            seedId?.let { seedId ->
                WalletItem(seedId)
            }
            Spacer(modifier = Modifier.height(50.dp))

            CustomBasicTextField(accountName.value, {
                accountName.value = it
            }, {
                accountName.value = ""
            })
        }

        AlgoKitPrimaryButton(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter),
            {
                onFinishClick()
            },
            text = localizedStringResource(Res.string.finish_account_creation),
        )
    }
}

@Composable
fun CustomBasicTextField(
    value: String,
    onValueChange: (String) -> Unit,
    onClearClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Text(
            style = typography.body.regular.sansMedium,
            color = AlgoKitTheme.colors.textMain,
            text = "Account Name",
        )

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
            )
            IconButton(onClick = onClearClick) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Clear",
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

@Composable
fun WalletItem(seedId: Int) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(4.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = AlgoKitTheme.colors.buttonSecondaryBg,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                painter = painterResource(Res.drawable.ic_wallet),
                contentDescription = null,
                tint = AlgoKitTheme.colors.textMain,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Wallet #$seedId",
                style = typography.body.regular.sans,
                color = AlgoKitTheme.colors.textMain,
            )
        }
    }
}

@Preview
@Composable
fun CreateAccountNameScreenPreview() {
    AlgoKitTheme {
        ScreenContent(
            navController = rememberNavController(),
            seedId = 0,
            accountName = remember { mutableStateOf("") },
            onFinishClick = {},
        )
    }
}
