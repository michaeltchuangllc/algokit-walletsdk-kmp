package com.michaeltchuang.walletsdk.ui.onboarding.screens

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.enter_your_recovery_passphrase
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_clipboard
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_info
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.recover
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.final_class.webview_multiplatform_mobile.webview.WebViewPlatform
import com.final_class.webview_multiplatform_mobile.webview.controller.rememberWebViewController
import com.michaeltchuang.walletsdk.core.account.domain.model.core.OnboardingAccountType
import com.michaeltchuang.walletsdk.ui.base.webview.openUrl
import com.michaeltchuang.walletsdk.core.account.domain.model.local.AccountMnemonic
import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants
import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants.SAMPLE_ALGO25_MNEMONIC
import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants.SAMPLE_BIP39_MNEMONIC
import com.michaeltchuang.walletsdk.core.foundation.utils.splitMnemonic
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.AlgoKitTopBar
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.button.AlgoKitButtonState
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.button.AlgoKitPrimaryButton
import com.michaeltchuang.walletsdk.ui.base.navigation.AlgoKitScreens
import com.michaeltchuang.walletsdk.ui.onboarding.viewmodels.RecoverPassphraseViewModel
import com.michaeltchuang.walletsdk.ui.settings.domain.localization.localizedStringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecoveryPhraseScreen(
    navController: NavController,
    accountType: AccountMnemonic.AccountType,
    mnemonicString: String,
    snackBar: (message: String) -> Unit,
) {
    val clipboardManager: ClipboardManager = LocalClipboardManager.current
    val viewModel: RecoverPassphraseViewModel = koinViewModel()

    val initialWordCount =
        when (accountType) {
            AccountMnemonic.AccountType.Algo25 -> 25
            AccountMnemonic.AccountType.Falcon24 -> 24
            else -> 24
        }

    var mnemonicList by rememberSaveable {
        mutableStateOf(
            if (mnemonicString.isNotEmpty()) {
                mnemonicString.splitMnemonic()
            } else {
                List(initialWordCount) { "" }
            },
        )
    }
    val webViewController by rememberWebViewController()
    WebViewPlatform(webViewController = webViewController)

    LaunchedEffect(Unit) {
        viewModel.viewEvent.collect {
            when (it) {
                is RecoverPassphraseViewModel.ViewEvent.NavigateToAccountNameScreen -> {
                    navController.navigate(AlgoKitScreens.CREATE_ACCOUNT_NAME.name)
                }

                is RecoverPassphraseViewModel.ViewEvent.NavigateToRecoverRegisteredAccountScreen -> {
                    navController.navigate(AlgoKitScreens.RECOVER_REGISTERED_ACCOUNTS_SCREEN.name)
                }

                is RecoverPassphraseViewModel.ViewEvent.ShowError -> {
                    snackBar(it.error)
                }
            }
        }
    }

    ScreenContent(
        navController = navController,
        accountType = accountType,
        mnemonicList = mnemonicList,
        onMnemonicChange = { newList -> mnemonicList = newList },
        onClipboardPaste = {
            clipboardManager.getText()?.text?.let {
                viewModel.onClipBoardPastedMnemonic(it) {
                    mnemonicList = it.splitMnemonic()
                }
            }
        },
        onRecover = {
            when (accountType) {
                AccountMnemonic.AccountType.Algo25 -> {
                    viewModel.onRecoverAccount(
                        mnemonicList.joinToString(" "),
                        OnboardingAccountType.Algo25,
                    )
                }

                AccountMnemonic.AccountType.Falcon24 -> {
                    viewModel.onRecoverAccount(
                        mnemonicList.joinToString(" "),
                        OnboardingAccountType.Falcon24,
                    )
                }

                AccountMnemonic.AccountType.HdKey -> {
                    viewModel.onRecoverAccount(
                        mnemonicList.joinToString(" "),
                        OnboardingAccountType.HdKey,
                    )
                }
            }
        },
        onLearnMore = {
            webViewController.openUrl(WalletSdkConstants.RECOVER_ACCOUNT_LEARN_MORE)
        },
    )
}

@Composable
internal fun ScreenContent(
    navController: NavController,
    accountType: AccountMnemonic.AccountType,
    mnemonicList: List<String>,
    onMnemonicChange: (List<String>) -> Unit,
    onClipboardPaste: () -> Unit,
    onRecover: () -> Unit,
    onLearnMore: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .background(color = AlgoKitTheme.colors.background)
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(horizontal = 16.dp),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                AlgoKitTopBar(
                    modifier = Modifier.weight(1f, fill = false),
                    onClick = { navController.popBackStack() },
                )
                Row(
                    modifier = Modifier.wrapContentSize(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onClipboardPaste,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            tint = AlgoKitTheme.colors.textMain,
                            painter = painterResource(Res.drawable.ic_clipboard),
                            contentDescription = "Paste from clipboard",
                            modifier = Modifier.size(24.dp),
                        )
                    }

                    IconButton(
                        onClick = onLearnMore,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            tint = AlgoKitTheme.colors.textMain,
                            painter = painterResource(Res.drawable.ic_info),
                            contentDescription = "Learn more",
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }

            RecoveryPhraseContent(
                modifier = Modifier.fillMaxHeight(0.8f),
                predefinedWords = mnemonicList,
                onWordChange = { index, value ->
                    val newList = mnemonicList.toMutableList()
                    while (newList.size <= index) {
                        newList.add("")
                    }
                    newList[index] = value
                    onMnemonicChange(newList)
                },
            )
            Spacer(modifier = Modifier.height(32.dp))
            val isValid = mnemonicList.all { it.isNotEmpty() }
            AlgoKitPrimaryButton(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.CenterHorizontally),
                onClick = onRecover,
                text = localizedStringResource(Res.string.recover),
                state = if (isValid) AlgoKitButtonState.ENABLED else AlgoKitButtonState.DISABLED,
            )
        }
    }
}

@Composable
fun RecoveryPhraseContent(
    modifier: Modifier,
    predefinedWords: List<String>,
    onWordChange: (index: Int, value: String) -> Unit,
) {
    Column(modifier = modifier) {
        Text(
            style = AlgoKitTheme.typography.title.regular.sansMedium,
            text = localizedStringResource(Res.string.enter_your_recovery_passphrase),
            color = AlgoKitTheme.colors.textMain,
        )
        Spacer(modifier = Modifier.height(32.dp))
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            val wordCount = predefinedWords.size
            items((wordCount + 1) / 2) { rowIndex ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    val leftIndex = rowIndex
                    val rightIndex = rowIndex + (wordCount + 1) / 2

                    if (leftIndex < wordCount) {
                        RecoveryWordField(
                            index = leftIndex,
                            value = predefinedWords[leftIndex],
                            onValueChange = { onWordChange(leftIndex, it) },
                            modifier = Modifier.weight(1f),
                        )
                    }

                    if (rightIndex < wordCount) {
                        RecoveryWordField(
                            index = rightIndex,
                            value = predefinedWords[rightIndex],
                            onValueChange = { onWordChange(rightIndex, it) },
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

@Composable
fun RecoveryWordField(
    index: Int,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .padding(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                modifier = Modifier.padding(4.dp),
                text = "${index + 1}",
                style = AlgoKitTheme.typography.title.regular.sans,
                fontSize = 14.sp,
                color = AlgoKitTheme.colors.textMain,
            )

            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(end = 16.dp),
                singleLine = true,
                textStyle =
                    LocalTextStyle.current.copy(
                        fontSize = 16.sp,
                        color = AlgoKitTheme.colors.textMain,
                    ),
                cursorBrush = SolidColor(AlgoKitTheme.colors.textMain),
                keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Text),
            )
        }
        HorizontalDivider(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            color = Color.Gray,
        )
    }
}

@Preview
@Composable
fun RecoveryPhrase24WordScreenPreview() {
    val words = SAMPLE_BIP39_MNEMONIC.split(" ")
    ScreenContent(
        navController = rememberNavController(),
        accountType = AccountMnemonic.AccountType.Falcon24,
        mnemonicList = words,
        onMnemonicChange = {},
        onClipboardPaste = {},
        onRecover = {},
        onLearnMore = {},
    )
}

@Preview
@Composable
fun RecoveryPhrase25WordScreenPreview() {
    val words = SAMPLE_ALGO25_MNEMONIC.split(" ")
    ScreenContent(
        navController = rememberNavController(),
        accountType = AccountMnemonic.AccountType.Algo25,
        mnemonicList = words,
        onMnemonicChange = {},
        onClipboardPaste = {},
        onRecover = {},
        onLearnMore = {},
    )
}
