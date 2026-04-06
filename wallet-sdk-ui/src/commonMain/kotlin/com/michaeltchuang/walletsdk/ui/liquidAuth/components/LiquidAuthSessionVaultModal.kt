package com.michaeltchuang.walletsdk.ui.liquidAuth.components

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_cross
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_lock
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.jetbrains.compose.resources.vectorResource
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun LiquidAuthSessionVaultModal(
    onDismiss: () -> Unit,
    onTopUpAndStream: (String) -> Unit = { _ -> },
    initialAmount: String = "2.22",
    quickAmounts: List<String> = listOf("0.888", "8.88"),
    currencyLabel: String = "USDC",
    isProcessing: Boolean = false,
    isDismissible: Boolean = true,
) {
    var topUpAmount by remember(initialAmount) { mutableStateOf(initialAmount) }

    val canDismiss = isDismissible && !isProcessing

    Dialog(
        onDismissRequest = {
            if (canDismiss) onDismiss()
        },
        properties =
            DialogProperties(
                dismissOnBackPress = canDismiss,
                dismissOnClickOutside = canDismiss,
                usePlatformDefaultWidth = false,
            ),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color(0x66001423))
                        .clickable(enabled = canDismiss) {
                            if (canDismiss) onDismiss()
                        },
            )

            Box(
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 11.dp)
                        .fillMaxWidth()
                        .widthIn(max = 356.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .border(1.dp, Color(0x4DB5E6E8), RoundedCornerShape(20.dp))
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color(0xFF2A3B4E), Color(0xFF0D2A46), Color(0xFF001423)),
                            ),
                        ),
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 24.dp, end = 24.dp, top = 52.dp, bottom = 0.dp),
                ) {
                    Text(
                        text = "Session Vault Locked",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 38.sp / 1.5f,
                        letterSpacing = (-0.8).sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Estimated 1 hour of watch time: ~1.50 USDC.",
                        color = Color(0xFFB5CFD4),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(22.dp))

                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color(0x1AF12D2D))
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFF12D2D)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("!", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        Text(
                            text = "Your Session Vault is empty.\nTop up now for exclusive features!",
                            color = Color.White,
                            fontSize = 14.sp,
                            lineHeight = 17.sp,
                        )
                    }

                    Spacer(Modifier.height(22.dp))
                    Text(
                        text = "QUICK AMOUNTS",
                        color = Color(0xFFB9EFEF),
                        fontSize = 10.sp,
                        letterSpacing = 1.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        QuickAmountButton(
                            modifier = Modifier.weight(1f),
                            value = quickAmounts.getOrElse(0) { "0.888" },
                            selected = topUpAmount == quickAmounts.getOrElse(0) { "0.888" },
                            onClick = { topUpAmount = quickAmounts.getOrElse(0) { "0.888" } },
                            enabled = !isProcessing,
                        )
                        QuickAmountButton(
                            modifier = Modifier.weight(1f),
                            value = quickAmounts.getOrElse(1) { "8.88" },
                            selected = topUpAmount == quickAmounts.getOrElse(1) { "8.88" },
                            onClick = { topUpAmount = quickAmounts.getOrElse(1) { "8.88" } },
                            enabled = !isProcessing,
                        )
                    }

                    Spacer(Modifier.height(22.dp))
                    Text(
                        text = "TOP UP AMOUNT",
                        color = Color(0xFFB9EFEF),
                        fontSize = 10.sp,
                        letterSpacing = 1.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(20.dp))
                                .background(Color(0x33FFFFFF))
                                .padding(horizontal = 15.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicTextField(
                            value = topUpAmount,
                            onValueChange = { topUpAmount = it },
                            enabled = !isProcessing,
                            singleLine = true,
                            textStyle =
                                TextStyle(
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 42.sp / 1.5f,
                                    letterSpacing = (-1).sp,
                                ),
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = currencyLabel,
                            color = Color(0xFFB9EFEF),
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                        )
                    }

                    Spacer(Modifier.height(24.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(15.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (canDismiss) {
                            Box(
                                modifier =
                                    Modifier
                                        .size(55.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(12.dp))
                                        .background(Color(0x1AFFFFFF))
                                        .clickable(onClick = onDismiss),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    vectorResource(Res.drawable.ic_cross),
                                    contentDescription = null,
                                    tint = Color(0xFFB9EFEF),
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }

                        Row(
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .height(55.dp)
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(Color(0xFF2D2DF1))
                                    .clickable(enabled = !isProcessing, onClick = { onTopUpAndStream(topUpAmount) }),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (isProcessing) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = "Signing...",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                    letterSpacing = (-0.2).sp,
                                )
                            } else {
                                Box(
                                    modifier =
                                        Modifier
                                            .size(19.dp)
                                            .clip(CircleShape)
                                            .border(2.dp, Color(0xFFB9EFEF), CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("v", color = Color(0xFFB9EFEF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = "Top-Up & Stream",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                    letterSpacing = (-0.2).sp,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(22.dp))
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .background(Color(0xFF001423)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "SECURED BY ALGORAND BLOCKCHAIN LAYER",
                            color = Color(0xFFB9EFEF),
                            fontSize = 10.sp,
                            letterSpacing = 2.sp,
                        )
                    }
                }

                Box(
                    modifier =
                        Modifier
                            .align(Alignment.TopCenter)
                            .offset(y = (-18).dp)
                            .size(58.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        vectorResource(Res.drawable.ic_lock),
                        contentDescription = null,
                        tint = Color(0xFF001423),
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickAmountButton(
    modifier: Modifier = Modifier,
    value: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean,
) {
    Column(
        modifier =
            modifier
                .height(72.dp)
                .clip(RoundedCornerShape(20.dp))
                .border(1.dp, if (selected) Color(0xFF3FD2EF) else Color(0x33FFFFFF), RoundedCornerShape(20.dp))
                .background(if (selected) Color(0x2A3FD2EF) else Color(0x1AFFFFFF))
                .clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = value,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 42.sp / 1.5f,
            letterSpacing = (-1.2).sp,
        )
        Text(
            text = "USDC",
            color = Color(0xFFB9EFEF),
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
        )
    }
}

@Preview
@Composable
private fun LiquidAuthSessionVaultModalPreviewDismissible() {
    LiquidAuthSessionVaultModal(
        onDismiss = {},
        isDismissible = true,
    )
}

@Preview
@Composable
private fun LiquidAuthSessionVaultModalPreviewLocked() {
    LiquidAuthSessionVaultModal(
        onDismiss = {},
        isDismissible = false,
    )
}
