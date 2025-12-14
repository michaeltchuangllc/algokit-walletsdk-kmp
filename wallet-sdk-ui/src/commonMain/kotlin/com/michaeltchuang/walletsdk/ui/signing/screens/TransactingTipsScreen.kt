package com.michaeltchuang.walletsdk.ui.signing.screens

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.for_more_information_on
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.i_understand
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_info
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.transacting_tips
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.we_notice_that_this
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.when_sending_to_an_address
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.final_class.webview_multiplatform_mobile.webview.WebViewPlatform
import com.final_class.webview_multiplatform_mobile.webview.controller.rememberWebViewController
import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants.TRANSACTIONS_LEARN_MORE
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme.typography
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.button.AlgoKitPrimaryButton
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.icon.AlgoKitIcon
import com.michaeltchuang.walletsdk.ui.base.webview.openUrl
import com.michaeltchuang.walletsdk.ui.settings.domain.localization.localizedStringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun TransactingTipsScreen(onUnderstandClick: () -> Unit) {
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
                    .padding(bottom = 75.dp)
                    .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            AlgoKitIcon(
                painter = painterResource(Res.drawable.ic_info),
                contentDescription = "Information Icon",
                tintColor = AlgoKitTheme.colors.buttonPrimaryBg,
                modifier = Modifier.size(85.dp),
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Title
            Text(
                text = localizedStringResource(Res.string.transacting_tips),
                color = AlgoKitTheme.colors.textMain,
                style = typography.body.large.sansMedium,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Description
            Text(
                text = localizedStringResource(Res.string.we_notice_that_this),
                color = AlgoKitTheme.colors.textGray,
                style = typography.body.regular.sans,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp),
            )

            Spacer(modifier = Modifier.height(32.dp))

            // First tip with checkmark
            TipItem(
                text = AnnotatedString(stringResource(Res.string.when_sending_to_an_address)),
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Second tip with checkmark
            TipItem(
                text =
                    buildAnnotatedString {
                        append(
                            "Exchanges change their deposit addresses frequently, and saved exchange addresses may no longer be in use. ",
                        )
                        withStyle(
                            style =
                                SpanStyle(
                                    color = Color(0xFFFF6D5F), // Salmon color for emphasis
                                    fontWeight = FontWeight.Medium,
                                ),
                        ) {
                            append("Ensure you're sending to the correct address.")
                        }
                    },
            )

            Spacer(modifier = Modifier.height(32.dp))

            MoreInfoClick()
        }

        // Bottom button
        AlgoKitPrimaryButton(
            onClick = onUnderstandClick,
            text = localizedStringResource(Res.string.i_understand),
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp),
        )
    }
}

@Composable
private fun TipItem(text: AnnotatedString) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        // Checkmark icon in circle
        Box(
            modifier =
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .border(
                        2.dp,
                        AlgoKitTheme.colors.layerGrayLighter,
                        CircleShape,
                    ), // Darker cyan border
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Check",
                tint = AlgoKitTheme.colors.buttonPrimaryBg,
                modifier = Modifier.size(16.dp),
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        // Tip text
        Text(
            text = text,
            color = AlgoKitTheme.colors.textMain,
            style = typography.body.regular.sans,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
fun MoreInfoClick(modifier: Modifier = Modifier) {
    val layoutResult =
        remember {
            mutableStateOf<TextLayoutResult?>(null)
        }
    val annotatedString = createAnnotatedString()
    val webViewController by rememberWebViewController()
    WebViewPlatform(webViewController = webViewController)
    Text(
        style = typography.footnote.sans,
        color = AlgoKitTheme.colors.textGray,
        modifier =
            modifier
                .pointerInput(annotatedString) {
                    detectTapGestures { pos ->
                        layoutResult.value?.let { layoutResult ->
                            // Adjust the position to account for padding
                            val adjustedPos =
                                pos.copy(x = pos.x - 25.dp.toPx(), y = pos.y - 15.dp.toPx())
                            val offset = layoutResult.getOffsetForPosition(adjustedPos)
                            annotatedString
                                .getStringAnnotations(
                                    tag = "TRANSACTIONS_LEARN_MORE",
                                    start = offset,
                                    end = offset + 1,
                                ).firstOrNull()
                                ?.let {
                                    webViewController.openUrl(TRANSACTIONS_LEARN_MORE)
                                }
                        }
                    }
                }.padding(vertical = 24.dp),
        text = annotatedString,
        onTextLayout = {
            layoutResult.value = it
        },
    )
}

@Composable
private fun createAnnotatedString() =
    buildAnnotatedString {
        val fullText =
            localizedStringResource(
                Res.string.for_more_information_on,
            )
        val tapHere = "tap here"

        val tapHereStartIndex = fullText.indexOf(tapHere)
        val tapHereEndIndex = tapHereStartIndex + tapHere.length

        append(fullText)

        addStyle(
            style =
                SpanStyle(
                    color = AlgoKitTheme.colors.linkPrimary,
                ),
            start = tapHereStartIndex,
            end = tapHereEndIndex,
        )
        addStringAnnotation(
            tag = "TRANSACTIONS_LEARN_MORE",
            annotation = TRANSACTIONS_LEARN_MORE,
            start = tapHereStartIndex,
            end = tapHereEndIndex,
        )
    }

@Preview
@Composable
fun TransactingTipsScreenPreview() {
    AlgoKitTheme {
        TransactingTipsScreen(
            onUnderstandClick = {},
        )
    }
}
