package com.michaeltchuang.walletsdk.ui.onboarding.screens

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.finish_account_creation
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.name_your_account
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.michaeltchuang.walletsdk.core.foundation.utils.Log
import com.michaeltchuang.walletsdk.core.foundation.utils.toShortenedAddress
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme.typography
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.AlgoKitTopBar
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.button.AlgoKitPrimaryButton
import com.michaeltchuang.walletsdk.ui.onboarding.viewmodels.AddressNamingViewModel
import com.michaeltchuang.walletsdk.ui.settings.domain.localization.localizedStringResource
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun AddressNamingScreen(
    navController: NavController,
    address: String,
    showSnackBar: (message: String) -> Unit,
    onFinish: () -> Unit,
) {
    val viewModel: AddressNamingViewModel = koinViewModel()
    val viewState = viewModel.state.collectAsStateWithLifecycle().value
    val accountName = remember { mutableStateOf(address.toShortenedAddress()) }

    LaunchedEffect(Unit) {
        viewModel.fetchAccountDetails(address)
    }

    when (viewState) {
        is AddressNamingViewModel.ViewState.Content -> {
            accountName.value = viewState.currentName
            ScreenContent(
                navController,
                accountName,
                onFinishClick = {
                    viewModel.saveCustomName(accountName.value)
                },
            )
        }

        is AddressNamingViewModel.ViewState.Idle -> {}
        is AddressNamingViewModel.ViewState.Loading -> {}
    }

    LaunchedEffect(Unit) {
        viewModel.viewEvent.collect { event ->
            when (event) {
                is AddressNamingViewModel.ViewEvent.FinishedAccountCreation -> {
                    onFinish()
                }

                is AddressNamingViewModel.ViewEvent.Error -> {
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
                text =
                    "Name your account to easily identify it while using the AlgoKit Wallet. " +
                        "These names are stored locally, and can only be seen by you.",
            )

            Spacer(modifier = Modifier.height(24.dp))
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

@Preview
@Composable
fun AddressNamingScreenPreview() {
    AlgoKitTheme {
        ScreenContent(
            navController = rememberNavController(),
            accountName = remember { mutableStateOf("") },
            onFinishClick = {},
        )
    }
}
