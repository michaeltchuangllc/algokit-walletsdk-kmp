package com.michaeltchuang.walletsdk.ui.liquidAuth.components

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.figma_ic_lock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.liquidStream.utils.streamMetricsCardStyle
import org.jetbrains.compose.resources.vectorResource

@Composable
internal fun StreamSettingsMetricsCard(
    showRevenueMetrics: Boolean,
    realTimeRate: String,
    streamRevenue: String,
    securedViaLabel: String,
    blockNumberLabel: String,
) {
    val colors = AlgoKitTheme.colors
    val style = streamMetricsCardStyle

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(style.rootModifier),
        verticalArrangement = Arrangement.spacedBy(style.sectionSpacing),
    ) {
        if (showRevenueMetrics) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(style.metricSpacing)) {
                    Text(
                        text = "REAL-TIME RATE",
                        fontSize = 12.sp,
                        color = colors.streamHostMetricLabel,
                        letterSpacing = 1.2.sp,
                    )
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(style.metricValueSpacing),
                    ) {
                        Text(
                            text = realTimeRate,
                            fontSize = style.valueFontSize,
                            lineHeight = style.valueLineHeight,
                            fontWeight = FontWeight.Bold,
                            color = colors.streamHostTitle,
                        )
                        Text(
                            text = "${style.unitTextPrefix}USDC/BLOCK",
                            fontSize = style.unitFontSize,
                            color = colors.streamHostAccent,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(style.metricSpacing),
                    horizontalAlignment = Alignment.End,
                ) {
                    Text(
                        text = "STREAM REVENUE",
                        fontSize = 12.sp,
                        color = colors.streamHostMetricLabel,
                        letterSpacing = 1.2.sp,
                    )
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(style.metricValueSpacing),
                    ) {
                        Text(
                            text = streamRevenue,
                            fontSize = style.valueFontSize,
                            lineHeight = style.valueLineHeight,
                            fontWeight = FontWeight.Bold,
                            color = colors.streamHostTitle,
                        )
                        Text(
                            text = "${style.unitTextPrefix}USDC",
                            fontSize = style.unitFontSize,
                            color = colors.streamHostAccent,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.streamHostDivider),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    vectorResource(Res.drawable.figma_ic_lock),
                    contentDescription = null,
                    tint = colors.streamHostBodyText,
                    modifier = Modifier.size(style.lockIconSize),
                )
                Text(
                    text = securedViaLabel,
                    fontSize = 12.sp,
                    color = colors.streamHostBodyText,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier =
                        Modifier
                            .size(6.dp)
                            .clip(RoundedCornerShape(50))
                            .background(colors.streamHostAccent),
                )
                if (style.useAnnotatedBlockLabel) {
                    Text(
                        text =
                            buildAnnotatedString {
                                append("Block ")
                                pushStyle(SpanStyle(fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline))
                                append(blockNumberLabel)
                                pop()
                            },
                        fontSize = 12.sp,
                        color = colors.streamHostBodyText,
                    )
                } else {
                    Text(
                        text = "Block $blockNumberLabel",
                        fontSize = 12.sp,
                        color = colors.streamHostBodyText,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}
