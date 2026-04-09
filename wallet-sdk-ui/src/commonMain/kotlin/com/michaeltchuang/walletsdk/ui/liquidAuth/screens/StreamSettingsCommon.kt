package com.michaeltchuang.walletsdk.ui.liquidAuth.screens

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.figma_ic_lock
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_every_block
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.liquidAuth.components.LiquidSegmentedTabs
import com.michaeltchuang.walletsdk.ui.liquidAuth.components.SegmentedTabItem
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.vectorResource

internal const val PAYOUT_EVERY_BLOCK_TAB_ID = "every_block"
internal const val PAYOUT_EVERY_256_BLOCKS_TAB_ID = "every_256_blocks"

private data class StreamMetricsCardStyle(
    val sectionSpacing: Dp,
    val metricSpacing: Dp,
    val valueFontSize: TextUnit,
    val valueLineHeight: TextUnit,
    val unitFontSize: TextUnit,
    val lockIconSize: Dp,
    val metricValueSpacing: Dp,
    val unitTextPrefix: String,
    val rootModifier: Modifier,
    val useAnnotatedBlockLabel: Boolean,
)

private val streamMetricsCardStyle =
    StreamMetricsCardStyle(
        sectionSpacing = 15.dp,
        metricSpacing = 5.dp,
        valueFontSize = 42.sp / 1.5f,
        valueLineHeight = 24.sp,
        unitFontSize = 12.sp,
        lockIconSize = 14.dp,
        metricValueSpacing = 4.dp,
        unitTextPrefix = "",
        rootModifier = Modifier,
        useAnnotatedBlockLabel = true,
    )

private val payoutFrequencyTabs =
    listOf(
        SegmentedTabItem(
            id = PAYOUT_EVERY_BLOCK_TAB_ID,
            icon = Res.drawable.ic_every_block,
            title = "Every Block",
            subtitle = "(~2.8 secs)",
        ),
        SegmentedTabItem(
            id = PAYOUT_EVERY_256_BLOCKS_TAB_ID,
            icon = Res.drawable.ic_every_block,
            title = "Every 256 Blocks",
            subtitle = "(~12 mins)",
        ),
    )

@Composable
internal fun StreamSettingsTopSquareIconButton(
    icon: DrawableResource,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
) {
    val colors = AlgoKitTheme.colors

    Box(
        modifier =
            modifier
                .size(45.dp)
                .clip(RoundedCornerShape(cornerRadius))
                .background(colors.streamHostCloseButtonBackground)
                .border(1.dp, colors.streamHostCloseButtonBorder, RoundedCornerShape(cornerRadius))
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            vectorResource(icon),
            contentDescription = null,
            tint = colors.streamHostCloseButtonIcon,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
internal fun StreamSettingsPayoutFrequencyBlock(
    selectedTabId: String,
    onPayoutFrequencyTabSelected: (String) -> Unit,
    footnoteText: String,
    footnoteStarFontSize: TextUnit = 12.sp,
    footnoteBodyFontSize: TextUnit = 12.sp,
    footnoteLineHeight: TextUnit = 18.sp,
    footnoteStarTopPadding: Dp = 1.dp,
) {
    val colors = AlgoKitTheme.colors

    Column(verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = "Payout Frequency",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = colors.streamHostTitle,
            )
            Text(
                text = "(PER REVENUE BATCH)",
                fontSize = 12.sp,
                color = colors.streamHostCaption,
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }

        LiquidSegmentedTabs(
            tabs = payoutFrequencyTabs,
            selectedTabId = selectedTabId,
            onTabSelected = onPayoutFrequencyTabSelected,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 10.dp),
            containerColor = colors.streamHostTabContainer,
            borderColor = colors.streamHostTabBorder,
            selectedTabColor = colors.streamHostTabSelected,
            selectedContentColor = colors.streamHostTabSelectedContent,
            unselectedIconColor = colors.streamHostTabUnselectedIcon,
            unselectedTitleColor = colors.streamHostTabUnselectedTitle,
            selectedSubtitleColor = colors.streamHostTabSelectedSubtitle,
            unselectedSubtitleColor = colors.streamHostTabUnselectedSubtitle,
            subtitleLineHeight = 16.sp,
            selectedSubtitleWeight = FontWeight.Medium,
            unselectedSubtitleWeight = FontWeight.Normal,
            containerCornerRadius = 21.dp,
            tabCornerRadius = 18.dp,
            usePerTabSelectedBackground = true,
        )

        StreamSettingsFootnoteText(
            text = footnoteText,
            starFontSize = footnoteStarFontSize,
            bodyFontSize = footnoteBodyFontSize,
            lineHeight = footnoteLineHeight,
            starTopPadding = footnoteStarTopPadding,
        )
    }
}

@Composable
internal fun StreamSettingsFootnoteText(
    text: String,
    modifier: Modifier = Modifier,
    starFontSize: TextUnit = 12.sp,
    bodyFontSize: TextUnit = 12.sp,
    lineHeight: TextUnit = 18.sp,
    starTopPadding: Dp = 1.dp,
) {
    val colors = AlgoKitTheme.colors

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "*",
            fontSize = starFontSize,
            color = colors.streamHostStarTint,
            modifier = Modifier.padding(top = starTopPadding),
        )
        Text(
            text = text,
            fontSize = bodyFontSize,
            color = colors.streamHostBodyText,
            lineHeight = lineHeight,
        )
    }
}

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
