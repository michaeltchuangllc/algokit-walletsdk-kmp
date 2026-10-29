package com.michaeltchuang.walletsdk.ui.accountdetails.screens

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.algorand_session_add_algo_hint
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.algorand_session_address
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.algorand_session_balance
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.algorand_session_copied
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.algorand_session_description
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.algorand_session_dispenser
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.algorand_session_error
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.algorand_session_funding
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.algorand_session_key_heading
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.algorand_session_missing
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.algorand_session_no_dispenser
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.algorand_session_receiving
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.algorand_session_refresh
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.algorand_session_send
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.algorand_session_send_error
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.algorand_session_signing
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.algorand_session_source
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.algorand_session_title
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.final_class.webview_multiplatform_mobile.webview.WebViewPlatform
import com.final_class.webview_multiplatform_mobile.webview.controller.rememberWebViewController
import com.michaeltchuang.walletsdk.core.deeplink.model.DeepLink
import com.michaeltchuang.walletsdk.core.network.model.AlgorandNetwork
import com.michaeltchuang.walletsdk.ui.accountdetails.viewmodels.AlgorandSessionKeyViewModel
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.AlgoKitTopBar
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.button.AlgoKitPrimaryButton
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.button.AlgoKitSecondaryButton
import com.michaeltchuang.walletsdk.ui.base.webview.openUrl
import com.michaeltchuang.walletsdk.ui.settings.domain.localization.localizedStringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun AlgorandSessionKeyScreen(
    address: String,
    onBack: () -> Unit,
    showSnackBar: (String) -> Unit,
    onSendAlgo: (DeepLink.AssetTransfer) -> Unit,
    viewModel: AlgorandSessionKeyViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val browser by rememberWebViewController()
    WebViewPlatform(webViewController = browser)
    val sendError = localizedStringResource(Res.string.algorand_session_send_error)
    val latestSend by rememberUpdatedState(onSendAlgo)
    val latestMessage by rememberUpdatedState(showSnackBar)
    LaunchedEffect(address) { viewModel.load(address) }
    LaunchedEffect(viewModel, sendError) {
        viewModel.viewEvent.collect { event ->
            when (event) {
                is AlgorandSessionKeyViewModel.ViewEvent.SendAlgo -> latestSend(event.transfer)
                AlgorandSessionKeyViewModel.ViewEvent.Error -> latestMessage(sendError)
            }
        }
    }
    AlgorandSessionKeyContent(
        state = state,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onSendAlgo = viewModel::onSendAlgo,
        onDispenser = browser::openUrl,
        showSnackBar = showSnackBar,
    )
}

@Composable
private fun AlgorandSessionKeyContent(
    state: AlgorandSessionKeyViewModel.ViewState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSendAlgo: () -> Unit,
    onDispenser: (String) -> Unit,
    showSnackBar: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val copied = localizedStringResource(Res.string.algorand_session_copied)
    Column(modifier.fillMaxSize().background(AlgoKitTheme.colors.background)) {
        AlgoKitTopBar(
            title = localizedStringResource(Res.string.algorand_session_title),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            onClick = onBack,
        )
        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(8.dp))
            SessionText(localizedStringResource(Res.string.algorand_session_description))
            Spacer(Modifier.height(16.dp))
            when (state) {
                AlgorandSessionKeyViewModel.ViewState.Loading ->
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AlgoKitTheme.colors.positive)
                    }
                AlgorandSessionKeyViewModel.ViewState.Error -> {
                    AlgorandSessionCard {
                        AlgorandSessionTitle(localizedStringResource(Res.string.algorand_session_missing))
                        SessionText(localizedStringResource(Res.string.algorand_session_error))
                        Spacer(Modifier.height(12.dp))
                        AlgoKitPrimaryButton(
                            modifier = Modifier.fillMaxWidth(),
                            text = localizedStringResource(Res.string.algorand_session_refresh),
                            onClick = onRefresh,
                        )
                    }
                }
                is AlgorandSessionKeyViewModel.ViewState.Content -> {
                    AlgorandSessionCard {
                        AlgorandSessionTitle(localizedStringResource(Res.string.algorand_session_key_heading))
                        SessionText(localizedStringResource(Res.string.algorand_session_source), fontSize = 12.sp)
                        Spacer(Modifier.height(12.dp))
                        SessionText(localizedStringResource(Res.string.algorand_session_address), fontSize = 12.sp)
                        Text(
                            text = state.address,
                            modifier =
                                Modifier.clickable {
                                    clipboard.setText(AnnotatedString(state.address))
                                    showSnackBar(copied)
                                },
                            color = AlgoKitTheme.colors.textMain,
                            fontSize = 13.sp,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SessionText(
                                localizedStringResource(Res.string.algorand_session_balance),
                                modifier = Modifier.weight(1f),
                                fontSize = 12.sp,
                            )
                            Text(
                                text = localizedStringResource(Res.string.algorand_session_refresh),
                                modifier = Modifier.clickable(onClick = onRefresh),
                                color = AlgoKitTheme.colors.positive,
                                fontSize = 13.sp,
                            )
                        }
                        SessionText(
                            "${formatUnits(state.balance?.toLongOrNull(), 6)} ALGO",
                            color = AlgoKitTheme.colors.textMain,
                        )
                        SessionText(
                            localizedStringResource(Res.string.algorand_session_add_algo_hint),
                            color = AlgoKitTheme.colors.textMain,
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                        AlgoKitPrimaryButton(
                            modifier = Modifier.fillMaxWidth(),
                            text = localizedStringResource(Res.string.algorand_session_send),
                            onClick = onSendAlgo,
                        )
                        Spacer(Modifier.height(8.dp))
                        SessionText(localizedStringResource(Res.string.algorand_session_signing), fontSize = 12.sp)
                        SessionText(localizedStringResource(Res.string.algorand_session_receiving), fontSize = 12.sp)
                        SessionText(localizedStringResource(Res.string.algorand_session_funding), fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(16.dp))
                    if (state.dispenserUrl != null) {
                        AlgoKitSecondaryButton(
                            modifier = Modifier.fillMaxWidth(),
                            text = localizedStringResource(Res.string.algorand_session_dispenser),
                            onClick = { onDispenser(state.dispenserUrl) },
                        )
                    } else {
                        SessionText(localizedStringResource(Res.string.algorand_session_no_dispenser))
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SessionText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = AlgoKitTheme.colors.textGray,
    fontSize: TextUnit = 14.sp,
) {
    Text(text, modifier = modifier, color = color, fontSize = fontSize)
}

@Composable
private fun AlgorandSessionCard(content: @Composable () -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .border(1.dp, AlgoKitTheme.colors.layerGrayLighter, RoundedCornerShape(12.dp))
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        content()
    }
}

@Composable
private fun AlgorandSessionTitle(text: String) {
    Text(text, color = AlgoKitTheme.colors.textMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
}

@Preview
@Composable
private fun AlgorandSessionKeyPreview() {
    AlgoKitTheme {
        AlgorandSessionKeyContent(
            state =
                AlgorandSessionKeyViewModel.ViewState.Content(
                    address = "7N54HZSGBRQF7FW6YNC6F5H42AT5OXN3F5OQDAXF6H6PDFHNXIEBCJFHOY",
                    balance = "1250000",
                    network = AlgorandNetwork.TESTNET,
                    dispenserUrl = "https://bank.testnet.algorand.network",
                ),
            onBack = {},
            onRefresh = {},
            onSendAlgo = {},
            onDispenser = {},
            showSnackBar = {},
        )
    }
}
