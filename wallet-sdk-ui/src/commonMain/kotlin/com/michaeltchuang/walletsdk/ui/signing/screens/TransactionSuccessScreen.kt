package com.michaeltchuang.walletsdk.ui.signing.screens

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.done
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.operation_completed
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.view_transaction_detail_in_pera_explorer
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.your_transaction_was
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.final_class.webview_multiplatform_mobile.webview.WebViewPlatform
import com.final_class.webview_multiplatform_mobile.webview.controller.rememberWebViewController
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme.typography
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.button.AlgoKitPrimaryButton
import com.michaeltchuang.walletsdk.ui.base.webview.openUrl
import com.michaeltchuang.walletsdk.ui.settings.domain.localization.localizedStringResource
import com.michaeltchuang.walletsdk.ui.signing.viewmodels.TransactionSuccessViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun TransactionSuccessScreen(
    transactionId: String,
    onDoneClick: () -> Unit,
) {
    val viewModel: TransactionSuccessViewModel = koinViewModel()
    val scope = rememberCoroutineScope()
    val webViewController by rememberWebViewController()
    WebViewPlatform(webViewController = webViewController)

    ScreenContent(
        transactionId = transactionId,
        onDoneClick = onDoneClick,
        onViewInExplorer = { txId ->
            scope.launch {
                webViewController.openUrl(viewModel.getExplorerBaseUrl() + "/tx/$txId")
            }
        },
    )
}

@Composable
fun ScreenContent(
    transactionId: String,
    onDoneClick: () -> Unit,
    onViewInExplorer: (String) -> Unit,
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
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 100.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Checkmark Icon
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Success",
                tint = Color(0xFF22D3EE), // Cyan color
                modifier =
                    Modifier
                        .size(72.dp)
                        .background(Color.Transparent),
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Success Message
            Text(
                text = localizedStringResource(Res.string.operation_completed),
                color = AlgoKitTheme.colors.textMain,
                style = typography.body.regular.sansMedium,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Subtext
            Text(
                text = localizedStringResource(Res.string.your_transaction_was),
                color = AlgoKitTheme.colors.textGrayLighter,
                style = typography.body.regular.sansMedium,
            )

            Spacer(modifier = Modifier.height(40.dp))
        }

        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
        ) {
            Text(
                text = localizedStringResource(Res.string.view_transaction_detail_in_pera_explorer),
                color = AlgoKitTheme.colors.textMain,
                style = typography.footnote.sansMedium,
                modifier =
                    Modifier.clickable {
                        onViewInExplorer(transactionId)
                    },
            )
            AlgoKitPrimaryButton(
                onClick = {
                    onDoneClick()
                },
                text = localizedStringResource(Res.string.done),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
            )
        }
    }
}

@Preview
@Composable
fun TransactionSuccessScreenPreview() {
    AlgoKitTheme {
        TransactionSuccessScreen("transactionId") {}
    }
}
