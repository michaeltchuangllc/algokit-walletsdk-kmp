package com.michaeltchuang.walletsdk.ui.liquidStream.components

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_cross
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_fingerprint
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
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
fun StreamViewerTopUpModel(
    balanceLabel: String = "42.85",
    quickAmounts: List<String> = listOf("0.888", "8.88"),
    initialTopUpAmount: String = "2.22",
    networkLabel: String = "TESTNET",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var topUpAmount by remember(initialTopUpAmount) {
        mutableStateOf(initialTopUpAmount)
    }
    val colors = AlgoKitTheme.colors

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(colors.streamHostOverlay)
                    .clickable { onDismiss() },
        )

        Column(
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.streamHostSheetBackground)
                    .border(
                        1.dp,
                        colors.streamHostSheetBorder,
                        RoundedCornerShape(20.dp),
                    ).clickable(enabled = false) {},
        ) {
            Column(
                modifier =
                    Modifier.padding(
                        start = 24.dp,
                        end = 24.dp,
                        top = 20.dp,
                        bottom = 28.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                TopUpHeader(balanceLabel = balanceLabel)
                TopUpWarning()
                QuickAmounts(
                    amounts = quickAmounts,
                    selectedAmount = topUpAmount,
                    onAmountSelected = { topUpAmount = it },
                )
                TopUpInput(
                    amount = topUpAmount,
                    onAmountChange = { topUpAmount = it },
                )
                SignTransactionCard()
                TopUpActions(
                    onDismiss = onDismiss,
                    onConfirm = { onConfirm(topUpAmount) },
                )
            }

            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(colors.streamHostSheetBackground)
                        .padding(vertical = 16.dp),
            ) {
                Text(
                    text = "SECURED BY ALGORAND $networkLabel",
                    style =
                        AlgoKitTheme.typography.caption.sansMedium
                            .copy(fontSize = 10.sp, letterSpacing = 2.sp),
                    color = colors.streamHostAccent,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

@Composable
private fun TopUpHeader(balanceLabel: String) {
    val colors = AlgoKitTheme.colors

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "LIQUID STREAM",
                style =
                    AlgoKitTheme.typography.caption.sansMedium.copy(
                        fontSize = 12.sp,
                        letterSpacing = 1.sp,
                    ),
                color = colors.streamHostCaption,
            )
            Text(
                text = "SessionVault",
                color = colors.streamHostTitle,
                style =
                    AlgoKitTheme.typography.title.regular.sansBold.copy(
                        fontSize = 28.sp,
                        lineHeight = 24.sp,
                    ),
            )
        }

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = "BALANCE",
                color = colors.streamHostCaption,
                style =
                    AlgoKitTheme.typography.caption.sansMedium.copy(
                        fontSize = 12.sp,
                        letterSpacing = 1.2.sp,
                    ),
            )
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = balanceLabel,
                    color = colors.streamHostTitle,
                    style =
                        AlgoKitTheme.typography.title.regular.sansBold.copy(
                            fontSize = 28.sp,
                            lineHeight = 24.sp,
                        ),
                )
                Text(
                    text = "USDC",
                    color = colors.streamHostAccent,
                    style =
                        AlgoKitTheme.typography.caption.sansBold
                            .copy(fontSize = 12.sp),
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun TopUpWarning() {
    val colors = AlgoKitTheme.colors

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(15.dp))
                .background(ColorPalette.SignalRed500Alpha10)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(ColorPalette.SignalRed500),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "!", color = ColorPalette.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            text = "Insufficient balance to maintain stream for more than 4 minutes.",
            color = colors.streamHostTitle,
            fontSize = 14.sp,
            lineHeight = 18.sp,
        )
    }
}

@Composable
private fun QuickAmounts(
    amounts: List<String>,
    selectedAmount: String,
    onAmountSelected: (String) -> Unit,
) {
    val colors = AlgoKitTheme.colors

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "QUICK AMOUNTS",
            color = colors.streamHostCaption,
            style =
                AlgoKitTheme.typography.caption.sansMedium
                    .copy(fontSize = 10.sp, letterSpacing = 1.sp),
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
                            .height(78.dp)
                            .clickable { onAmountSelected(amount) },
                    shape = RoundedCornerShape(20.dp),
                    color = if (selected) colors.streamHostTabContainer else ColorPalette.Transparent,
                    border =
                        BorderStroke(
                            width = 1.dp,
                            color = if (selected) colors.streamHostTabBorder else colors.streamHostSheetBorder,
                        ),
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(text = amount, color = colors.streamHostTitle, fontSize = 42.sp / 1.5f, lineHeight = 24.sp)
                        Text(
                            text = "USDC",
                            color = colors.streamHostCaption,
                            style =
                                AlgoKitTheme.typography.caption.sansMedium
                                    .copy(fontSize = 12.sp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TopUpInput(
    amount: String,
    onAmountChange: (String) -> Unit,
) {
    val colors = AlgoKitTheme.colors

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "TOP UP AMOUNT",
            color = colors.streamHostCaption,
            style =
                AlgoKitTheme.typography.caption.sansMedium.copy(
                    fontSize = 10.sp,
                    letterSpacing = 1.sp,
                ),
        )
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.streamHostTabContainer)
                    .border(
                        1.dp,
                        colors.streamHostSheetBorder,
                        RoundedCornerShape(20.dp),
                    ).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = amount,
                onValueChange = { value ->
                    onAmountChange(value.filter { it.isDigit() || it == '.' }.take(7))
                },
                singleLine = true,
                textStyle =
                    AlgoKitTheme.typography.title.regular.sansBold.copy(
                        color = colors.streamHostTitle,
                        fontSize = 28.sp,
                        lineHeight = 24.sp,
                    ),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "USDC",
                color = colors.streamHostAccent,
                style =
                    AlgoKitTheme.typography.caption.sansBold
                        .copy(fontSize = 12.sp),
            )
        }
    }
}

@Composable
private fun SignTransactionCard() {
    val colors = AlgoKitTheme.colors

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(163.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(colors.streamHostTabContainer)
                .border(1.dp, colors.streamHostSheetBorder, RoundedCornerShape(20.dp))
                .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier =
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.streamHostSheetBackground),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                vectorResource(Res.drawable.ic_fingerprint),
                contentDescription = null,
                tint = colors.streamHostTabSelected,
                modifier = Modifier.size(24.dp),
            )
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Sign Transaction",
                color = colors.streamHostTitle,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text =
                    "Use Biometrics or Passkey to authorize the\ntransfer from Main Wallet to" +
                        " Session Vault.",
                color = colors.streamHostBodyText,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
        }
    }
}

@Composable
private fun TopUpActions(
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
                    .border(
                        1.dp,
                        colors.streamHostSheetBorder,
                        RoundedCornerShape(12.dp),
                    ).clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                vectorResource(Res.drawable.ic_cross),
                contentDescription = null,
                tint = colors.streamHostCloseButtonIcon,
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
            Box(
                modifier =
                    Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .border(2.dp, ColorPalette.WhiteAlpha60, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "✓",
                    color = ColorPalette.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.size(10.dp))
            Text(
                text = "Top-Up",
                color = ColorPalette.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp,
            )
        }
    }
}

@Preview
@Composable
private fun StreamViewerTopUpSheetLightPreview() {
    AlgoKitTheme {
        CompositionLocalProvider(
            LocalThemeIsDark provides mutableStateOf(false),
            LocalCustomColors provides AlgoKitLightColor,
        ) {
            StreamViewerTopUpModel(
                onDismiss = {},
                onConfirm = {},
            )
        }
    }
}

@Preview
@Composable
private fun StreamViewerTopUpSheetDarkPreview() {
    AlgoKitTheme {
        CompositionLocalProvider(
            LocalThemeIsDark provides mutableStateOf(true),
            LocalCustomColors provides AlgoKitDarkColor,
        ) {
            StreamViewerTopUpModel(
                onDismiss = {},
                onConfirm = {},
            )
        }
    }
}
