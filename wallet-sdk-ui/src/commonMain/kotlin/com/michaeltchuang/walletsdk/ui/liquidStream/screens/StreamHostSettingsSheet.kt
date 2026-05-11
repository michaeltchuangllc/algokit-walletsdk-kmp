package com.michaeltchuang.walletsdk.ui.liquidStream.screens

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_cross
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_free
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_usdc
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitDarkColor
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitLightColor
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.LocalCustomColors
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.LocalThemeIsDark
import com.michaeltchuang.walletsdk.ui.liquidStream.components.LiquidRingToggle
import com.michaeltchuang.walletsdk.ui.liquidStream.components.LiquidSegmentedTabs
import com.michaeltchuang.walletsdk.ui.liquidStream.components.SegmentedTabItem
import com.michaeltchuang.walletsdk.ui.liquidStream.components.StreamSettingsFootnoteText
import com.michaeltchuang.walletsdk.ui.liquidStream.components.StreamSettingsMetricsCard
import com.michaeltchuang.walletsdk.ui.liquidStream.components.StreamSettingsPayoutFrequencyBlock
import com.michaeltchuang.walletsdk.ui.liquidStream.components.StreamSettingsTopSquareIconButton
import com.michaeltchuang.walletsdk.ui.liquidStream.utils.PAYOUT_EVERY_256_BLOCKS_TAB_ID
import org.jetbrains.compose.ui.tooling.preview.Preview

private const val STREAM_COST_FREE_TAB_ID = "free"
private const val STREAM_COST_PAID_TAB_ID = "paid"

private val streamCostTabs =
    listOf(
        SegmentedTabItem(
            id = STREAM_COST_FREE_TAB_ID,
            icon = Res.drawable.ic_free,
            title = "FREE",
        ),
        SegmentedTabItem(
            id = STREAM_COST_PAID_TAB_ID,
            icon = Res.drawable.ic_usdc,
            title = "8 micro-USDC",
        ),
    )

@Composable
fun StreamHostSettingsSheet(
    selectedStreamCostTabId: String,
    selectedPayoutFrequencyTabId: String,
    subsidizeViewerFeesEnabled: Boolean,
    realTimeRate: String,
    streamRevenue: String,
    securedViaLabel: String,
    blockNumberLabel: String,
    onStreamCostTabSelected: (String) -> Unit,
    onPayoutFrequencyTabSelected: (String) -> Unit,
    onSubsidizeViewerFeesChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AlgoKitTheme.colors

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(colors.streamHostOverlay)
                    .clickable { onDismiss() },
        )
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            colors =
                                listOf(
                                    colors.streamHostGlowEdge,
                                    colors.streamHostGlowCenter,
                                    colors.streamHostGlowEdge,
                                ),
                        ),
                    ),
        )

        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp))
                    .background(colors.streamHostSheetBackground)
                    .border(
                        width = 1.dp,
                        color = colors.streamHostSheetBorder,
                        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
                    ).verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "Host Settings",
                        fontSize = 48.sp / 2f,
                        fontWeight = FontWeight.Bold,
                        color = colors.streamHostTitle,
                    )
                    Text(
                        text = "Manage your stream",
                        fontSize = 13.sp,
                        color = colors.streamHostSecondaryText,
                    )
                }
                StreamSettingsTopSquareIconButton(
                    icon = Res.drawable.ic_cross,
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }

            StreamSettingsMetricsCard(
                showRevenueMetrics = selectedStreamCostTabId == STREAM_COST_PAID_TAB_ID,
                realTimeRate = realTimeRate,
                streamRevenue = streamRevenue,
                securedViaLabel = securedViaLabel,
                blockNumberLabel = blockNumberLabel,
            )

            StreamCostBlock(
                selectedTabId = selectedStreamCostTabId,
                subsidizeViewerFeesEnabled = subsidizeViewerFeesEnabled,
                onStreamCostTabSelected = onStreamCostTabSelected,
                onSubsidizeViewerFeesChanged = onSubsidizeViewerFeesChanged,
            )

            PayoutFrequencyBlock(
                selectedTabId = selectedPayoutFrequencyTabId,
                onPayoutFrequencyTabSelected = onPayoutFrequencyTabSelected,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Box(
                modifier =
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(width = 140.dp, height = 5.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.streamHostHandle),
            )
        }
    }
}

@Composable
private fun StreamCostBlock(
    selectedTabId: String,
    subsidizeViewerFeesEnabled: Boolean,
    onStreamCostTabSelected: (String) -> Unit,
    onSubsidizeViewerFeesChanged: (Boolean) -> Unit,
) {
    val colors = AlgoKitTheme.colors

    Column(verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = "Stream Cost",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = colors.streamHostTitle,
            )
            Text(
                text = "(PER BLOCK)",
                fontSize = 12.sp,
                color = colors.streamHostCaption,
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }

        LiquidSegmentedTabs(
            tabs = streamCostTabs,
            selectedTabId = selectedTabId,
            onTabSelected = onStreamCostTabSelected,
            modifier = Modifier.fillMaxWidth(),
            containerColor = colors.streamHostTabContainer,
            borderColor = colors.streamHostTabBorder,
            selectedTabColor = colors.streamHostTabSelected,
            selectedContentColor = colors.streamHostTabSelectedContent,
            unselectedIconColor = colors.streamHostTabUnselectedIcon,
            unselectedTitleColor = colors.streamHostTabUnselectedTitle,
            selectedSubtitleColor = colors.streamHostTabSelectedSubtitle,
            unselectedSubtitleColor = colors.streamHostTabUnselectedSubtitle,
            containerCornerRadius = 21.dp,
            tabCornerRadius = 18.dp,
            contentPadding = PaddingValues(vertical = 14.dp),
            usePerTabSelectedBackground = true,
        )

        StreamSettingsFootnoteText(text = "Est. 1 Million blocks/month. 8 micro-USDC is ~$8 USDC monthly.")

        if (selectedTabId == STREAM_COST_PAID_TAB_ID) {
            Surface(shape = RoundedCornerShape(16.dp), color = colors.streamHostCardBackground) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        LiquidRingToggle(
                            checked = subsidizeViewerFeesEnabled,
                            onCheckedChange = onSubsidizeViewerFeesChanged,
                        )
                        Text(
                            text = "Subsidize viewer transaction fees",
                            fontSize = 16.sp,
                            color = colors.streamHostCardHeading,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "The streamer covers 0.001 ALGO gas fee,\nso viewers can enjoy streams without\nworrying about costs.",
                        fontSize = 12.sp,
                        color = colors.streamHostCardBody,
                        lineHeight = 20.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun PayoutFrequencyBlock(
    selectedTabId: String,
    onPayoutFrequencyTabSelected: (String) -> Unit,
) {
    StreamSettingsPayoutFrequencyBlock(
        selectedTabId = selectedTabId,
        onPayoutFrequencyTabSelected = onPayoutFrequencyTabSelected,
        footnoteText = "Batching payouts every 256 blocks saves you ~ $4,000 USD/year\nin transaction fees compared to every block.",
    )
}

@Preview
@Composable
private fun StreamHostSettingsSheetPaidDarkPreview() {
    AlgoKitTheme {
        CompositionLocalProvider(
            LocalThemeIsDark provides mutableStateOf(true),
            LocalCustomColors provides AlgoKitDarkColor,
        ) {
            StreamHostSettingsSheet(
                selectedStreamCostTabId = STREAM_COST_PAID_TAB_ID,
                selectedPayoutFrequencyTabId = PAYOUT_EVERY_256_BLOCKS_TAB_ID,
                subsidizeViewerFeesEnabled = false,
                realTimeRate = "0.42",
                streamRevenue = "+1.402.15",
                securedViaLabel = "Secured via Algorand Mainnet",
                blockNumberLabel = "#38291041",
                onStreamCostTabSelected = {},
                onPayoutFrequencyTabSelected = {},
                onSubsidizeViewerFeesChanged = {},
                onDismiss = {},
            )
        }
    }
}

@Preview
@Composable
private fun StreamHostSettingsSheetPaidLightPreview() {
    AlgoKitTheme {
        CompositionLocalProvider(
            LocalThemeIsDark provides mutableStateOf(false),
            LocalCustomColors provides AlgoKitLightColor,
        ) {
            StreamHostSettingsSheet(
                selectedStreamCostTabId = STREAM_COST_PAID_TAB_ID,
                selectedPayoutFrequencyTabId = PAYOUT_EVERY_256_BLOCKS_TAB_ID,
                subsidizeViewerFeesEnabled = false,
                realTimeRate = "0.42",
                streamRevenue = "+1.402.15",
                securedViaLabel = "Secured via Algorand Mainnet",
                blockNumberLabel = "#38291041",
                onStreamCostTabSelected = {},
                onPayoutFrequencyTabSelected = {},
                onSubsidizeViewerFeesChanged = {},
                onDismiss = {},
            )
        }
    }
}

@Preview
@Composable
private fun StreamHostSettingsSheetFreeDarkPreview() {
    AlgoKitTheme {
        CompositionLocalProvider(
            LocalThemeIsDark provides mutableStateOf(true),
            LocalCustomColors provides AlgoKitDarkColor,
        ) {
            StreamHostSettingsSheet(
                selectedStreamCostTabId = STREAM_COST_FREE_TAB_ID,
                selectedPayoutFrequencyTabId = PAYOUT_EVERY_256_BLOCKS_TAB_ID,
                subsidizeViewerFeesEnabled = false,
                realTimeRate = "0.42",
                streamRevenue = "+1.402.15",
                securedViaLabel = "Secured via Algorand Mainnet",
                blockNumberLabel = "#38291041",
                onStreamCostTabSelected = {},
                onPayoutFrequencyTabSelected = {},
                onSubsidizeViewerFeesChanged = {},
                onDismiss = {},
            )
        }
    }
}

@Preview
@Composable
private fun StreamHostSettingsSheetFreeLightPreview() {
    AlgoKitTheme {
        CompositionLocalProvider(
            LocalThemeIsDark provides mutableStateOf(false),
            LocalCustomColors provides AlgoKitLightColor,
        ) {
            StreamHostSettingsSheet(
                selectedStreamCostTabId = STREAM_COST_FREE_TAB_ID,
                selectedPayoutFrequencyTabId = PAYOUT_EVERY_256_BLOCKS_TAB_ID,
                subsidizeViewerFeesEnabled = false,
                realTimeRate = "0.42",
                streamRevenue = "+1.402.15",
                securedViaLabel = "Secured via Algorand Mainnet",
                blockNumberLabel = "#38291041",
                onStreamCostTabSelected = {},
                onPayoutFrequencyTabSelected = {},
                onSubsidizeViewerFeesChanged = {},
                onDismiss = {},
            )
        }
    }
}
