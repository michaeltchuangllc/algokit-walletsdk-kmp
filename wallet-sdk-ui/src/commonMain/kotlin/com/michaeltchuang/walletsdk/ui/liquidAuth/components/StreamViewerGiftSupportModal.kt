package com.michaeltchuang.walletsdk.ui.liquidAuth.components

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_cross
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_gift
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitDarkColor
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitLightColor
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.ColorPalette
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.LocalCustomColors
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.LocalThemeIsDark
import org.jetbrains.compose.resources.vectorResource
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun StreamViewerGiftSupportModal(
    balanceLabel: String = "42.85",
    quickAmounts: List<String> = listOf("0.888", "8.88"),
    initialSelectedAmount: String = "0.888",
    onDismiss: () -> Unit,
    onSelectedAmountChanged: (String) -> Unit = {},
    onConfirm: (String) -> Unit,
) {
    var selectedAmount by remember(initialSelectedAmount, quickAmounts) {
        mutableStateOf(quickAmounts.find { it == initialSelectedAmount } ?: quickAmounts.firstOrNull().orEmpty())
    }
    var customAmount by remember { mutableStateOf("") }
    val amountToSend = customAmount.ifBlank { selectedAmount }
    val colors = AlgoKitTheme.colors

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(colors.streamHostOverlay)
                    .clickable(onClick = onDismiss),
        )

        Column(
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.streamHostSheetBackground)
                    .border(1.dp, colors.streamHostSheetBorder, RoundedCornerShape(20.dp))
                    .clickable(enabled = false) {},
        ) {
            Column(
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                GiftSupportHeader(balanceLabel = balanceLabel)
                GiftQuickAmounts(
                    amounts = quickAmounts,
                    selectedAmount = selectedAmount,
                    onAmountSelected = {
                        selectedAmount = it
                        customAmount = ""
                        onSelectedAmountChanged(it)
                    },
                )
                GiftAmountInput(
                    amount = customAmount,
                    onAmountChange = { customAmount = it },
                )
                GiftActions(
                    amountToSend = amountToSend,
                    onDismiss = onDismiss,
                    onConfirm = {
                        if (amountToSend.isNotBlank()) onConfirm(amountToSend)
                    },
                )
            }
        }
    }
}

@Composable
private fun GiftSupportHeader(balanceLabel: String) {
    val colors = AlgoKitTheme.colors

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "GIFT SUPPORTER",
                style = AlgoKitTheme.typography.caption.sansMedium.copy(fontSize = 12.sp, letterSpacing = 1.sp),
                color = colors.streamHostCaption,
            )
            Text(
                text = "Send Support",
                color = colors.streamHostTitle,
                style = AlgoKitTheme.typography.title.regular.sansBold.copy(fontSize = 28.sp, lineHeight = 24.sp),
            )
        }

        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                text = "BALANCE",
                color = colors.streamHostCaption,
                style = AlgoKitTheme.typography.caption.sansMedium.copy(fontSize = 12.sp, letterSpacing = 1.2.sp),
            )
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = balanceLabel,
                    color = colors.streamHostTitle,
                    style = AlgoKitTheme.typography.title.regular.sansBold.copy(fontSize = 28.sp, lineHeight = 24.sp),
                )
                Text(
                    text = "USDC",
                    color = colors.streamHostAccent,
                    style = AlgoKitTheme.typography.caption.sansBold.copy(fontSize = 12.sp),
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun GiftQuickAmounts(
    amounts: List<String>,
    selectedAmount: String,
    onAmountSelected: (String) -> Unit,
) {
    val colors = AlgoKitTheme.colors

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "QUICK AMOUNTS",
            color = colors.streamHostCaption,
            style = AlgoKitTheme.typography.caption.sansMedium.copy(fontSize = 10.sp, letterSpacing = 1.sp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            amounts.forEach { amount ->
                val selected = amount == selectedAmount
                Surface(
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(72.dp)
                            .clickable { onAmountSelected(amount) },
                    shape = RoundedCornerShape(20.dp),
                    color = if (selected) colors.streamHostGiftQuickAmountSelectedBackground else ColorPalette.Transparent,
                    border =
                        BorderStroke(
                            width = 1.dp,
                            color = if (selected) colors.streamHostGiftQuickAmountSelectedBackground else colors.streamHostSheetBorder,
                        ),
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = amount,
                            color = if (selected) colors.streamHostGiftQuickAmountSelectedText else colors.streamHostTitle,
                            style = AlgoKitTheme.typography.title.regular.sansBold.copy(fontSize = 28.sp, lineHeight = 24.sp),
                        )
                        Text(
                            text = "USDC",
                            color = if (selected) colors.streamHostGiftQuickAmountSelectedCurrency else colors.streamHostCaption,
                            style = AlgoKitTheme.typography.caption.sansMedium.copy(fontSize = 12.sp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GiftAmountInput(
    amount: String,
    onAmountChange: (String) -> Unit,
) {
    val colors = AlgoKitTheme.colors

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "OR ENTER A CUSTOM AMOUNT",
            color = colors.streamHostCaption,
            style = AlgoKitTheme.typography.caption.sansMedium.copy(fontSize = 10.sp, letterSpacing = 1.sp),
        )
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.streamHostSheetBorder)
                    .border(1.dp, colors.streamHostSheetBorder, RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = amount,
                onValueChange = { value -> onAmountChange(value.filter { it.isDigit() || it == '.' }.take(7)) },
                singleLine = true,
                textStyle = AlgoKitTheme.typography.body.regular.sansMedium.copy(color = colors.streamHostTitle, fontSize = 14.sp),
                modifier = Modifier.weight(1f),
                decorationBox = { innerTextField ->
                    if (amount.isBlank()) {
                        Text(
                            text = "Enter amount",
                            color = colors.streamHostCaption,
                            style = AlgoKitTheme.typography.body.regular.sansMedium.copy(fontSize = 14.sp),
                        )
                    }
                    innerTextField()
                },
            )
            Text(
                text = "USDC",
                color = colors.streamHostAccent,
                style = AlgoKitTheme.typography.caption.sansBold.copy(fontSize = 24.sp / 2f),
            )
        }
    }
}

@Composable
private fun GiftActions(
    amountToSend: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = AlgoKitTheme.colors

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(55.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, colors.streamHostSheetBorder, RoundedCornerShape(12.dp))
                    .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                vectorResource(Res.drawable.ic_cross),
                contentDescription = null,
                tint = colors.streamHostTabSelected,
                modifier = Modifier.size(24.dp),
            )
        }
        Row(
            modifier =
                Modifier
                    .weight(1f)
                    .height(55.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.streamHostTabSelected)
                    .clickable(onClick = onConfirm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                vectorResource(Res.drawable.ic_gift),
                contentDescription = null,
                tint = ColorPalette.White,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.size(10.dp))
            Text(
                text =
                    buildAnnotatedString {
                        append("Send ")
                        pushStyle(
                            SpanStyle(
                                color = colors.streamHostCardBackground,
                                textDecoration = TextDecoration.Underline,
                            ),
                        )
                        append("$amountToSend USDC")
                        pop()
                        append(" Gift")
                    },
                color = ColorPalette.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp,
            )
        }
    }
}

@Preview
@Composable
private fun StreamViewerGiftSupportModalLightPreview() {
    AlgoKitTheme {
        CompositionLocalProvider(
            LocalThemeIsDark provides mutableStateOf(false),
            LocalCustomColors provides AlgoKitLightColor,
        ) {
            StreamViewerGiftSupportModal(onDismiss = {}, onConfirm = {})
        }
    }
}

@Preview
@Composable
private fun StreamViewerGiftSupportModalDarkPreview() {
    AlgoKitTheme {
        CompositionLocalProvider(
            LocalThemeIsDark provides mutableStateOf(true),
            LocalCustomColors provides AlgoKitDarkColor,
        ) {
            StreamViewerGiftSupportModal(onDismiss = {}, onConfirm = {})
        }
    }
}
