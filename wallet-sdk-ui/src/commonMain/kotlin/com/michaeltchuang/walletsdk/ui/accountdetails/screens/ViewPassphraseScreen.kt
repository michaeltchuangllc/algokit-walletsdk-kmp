package com.michaeltchuang.walletsdk.ui.accountdetails.screens

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.passphrase
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
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants.SAMPLE_ALGO25_MNEMONIC
import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants.SAMPLE_BIP39_MNEMONIC
import com.michaeltchuang.walletsdk.ui.accountdetails.viewmodels.ViewPassphraseViewModel
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.AlgoKitTopBar
import com.michaeltchuang.walletsdk.ui.onboarding.components.CenteredLoader
import com.michaeltchuang.walletsdk.ui.settings.domain.localization.localizedStringResource
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ViewPassphraseScreen(
    navController: NavController,
    address: String,
) {
    val viewModel: ViewPassphraseViewModel = koinViewModel()
    val viewState = viewModel.state.collectAsStateWithLifecycle().value
    LaunchedEffect(Unit) {
        viewModel.initViewState(address)
    }

    ScreenContent(
        navController = navController,
        viewState = viewState,
    )
}

@Composable
fun ScreenContent(
    navController: NavController,
    viewState: ViewPassphraseViewModel.ViewState,
) {
    when (viewState) {
        is ViewPassphraseViewModel.ViewState.Idle -> {}

        is ViewPassphraseViewModel.ViewState.Loading -> {
            CenteredLoader()
        }

        is ViewPassphraseViewModel.ViewState.Content -> {
            ViewPassphraseContent(navController, viewState.mnemonicWords)
        }
    }
}

@Composable
fun ViewPassphraseContent(
    navController: NavController,
    predefinedWords: List<String>,
) {
    Box(Modifier.fillMaxSize().background(AlgoKitTheme.colors.background)) {
        Column(
            modifier =
                Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth(),
        ) {
            AlgoKitTopBar(title = localizedStringResource(Res.string.passphrase)) {
                navController.popBackStack()
            }
            Spacer(modifier = Modifier.height(16.dp))

            Box(
                modifier =
                    Modifier
                        .background(
                            color = AlgoKitTheme.colors.layerGrayLighter,
                            shape = RoundedCornerShape(8.dp),
                        ).padding(16.dp),
            ) {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.wrapContentSize(),
                ) {
                    val wordCount = if (predefinedWords.isNotEmpty()) predefinedWords.size else 25
                    items((wordCount + 1) / 2) { rowIndex ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            val leftIndex = rowIndex
                            val rightIndex = rowIndex + (wordCount + 1) / 2

                            if (leftIndex < wordCount) {
                                ViewPassphraseWordField(
                                    index = leftIndex,
                                    value = if (predefinedWords.isNotEmpty()) predefinedWords[leftIndex] else "",
                                    onValueChange = { /*recoveryWords[leftIndex] = it*/ },
                                    modifier = Modifier.weight(1f),
                                )
                            }

                            if (rightIndex < wordCount) {
                                ViewPassphraseWordField(
                                    index = rightIndex,
                                    value = if (predefinedWords.isNotEmpty()) predefinedWords[rightIndex] else "",
                                    onValueChange = { /* recoveryWords[rightIndex] = it*/ },
                                    modifier = Modifier.weight(1f),
                                )
                            } else {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ViewPassphraseWordField(
    index: Int,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(2.dp),
    ) {
        Text(
            text = "${index + 1}",
            style = AlgoKitTheme.typography.title.regular.sans,
            fontSize = 14.sp,
            color = AlgoKitTheme.colors.textMain,
        )

        Text(
            modifier = Modifier.padding(start = 8.dp),
            text = value,
            fontSize = 16.sp,
            color = AlgoKitTheme.colors.textMain,
            style = AlgoKitTheme.typography.title.regular.sans,
        )
    }
}

@Preview
@Composable
fun ViewPassphrase24WordScreenScreenPreview() {
    val words = SAMPLE_BIP39_MNEMONIC.split(" ")
    AlgoKitTheme {
        ScreenContent(
            navController = rememberNavController(),
            viewState = ViewPassphraseViewModel.ViewState.Content(words),
        )
    }
}

@Preview
@Composable
fun ViewPassphrase25WordScreenScreenPreview() {
    val words = SAMPLE_ALGO25_MNEMONIC.split(" ")
    AlgoKitTheme {
        ScreenContent(
            navController = rememberNavController(),
            viewState = ViewPassphraseViewModel.ViewState.Content(words),
        )
    }
}
