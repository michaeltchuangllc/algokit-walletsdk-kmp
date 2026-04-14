package com.michaeltchuang.walletsdk.ui.liquidAuth.components

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_cross
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitDarkColor
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitLightColor
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.LocalCustomColors
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.LocalThemeIsDark
import com.michaeltchuang.walletsdk.ui.liquidAuth.utils.PAYOUT_EVERY_256_BLOCKS_TAB_ID
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun StreamViewerSettingsSheet(
    selectedPayoutFrequencyTabId: String,
    willingToBeRelayerEnabled: Boolean,
    realTimeRate: String,
    streamRevenue: String,
    securedViaLabel: String,
    blockNumberLabel: String,
    onPayoutFrequencyTabSelected: (String) -> Unit,
    onWillingToBeRelayerChanged: (Boolean) -> Unit,
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

        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 40.dp, topEnd = 40.dp))
                    .background(colors.streamHostSheetBackground)
                    .border(
                        width = 1.dp,
                        color = colors.streamHostSheetBorder,
                        shape = RoundedCornerShape(topStart = 40.dp, topEnd = 40.dp),
                    ).padding(top = 24.dp, start = 24.dp, end = 24.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            ViewerSettingsHeader(onDismiss = onDismiss)
            StreamSettingsMetricsCard(
                showRevenueMetrics = willingToBeRelayerEnabled,
                realTimeRate = realTimeRate,
                streamRevenue = streamRevenue,
                securedViaLabel = securedViaLabel,
                blockNumberLabel = blockNumberLabel,
            )
            RelayerCard(
                enabled = willingToBeRelayerEnabled,
                onEnabledChanged = onWillingToBeRelayerChanged,
            )
            ViewerPayoutFrequencyBlock(
                selectedTabId = selectedPayoutFrequencyTabId,
                onPayoutFrequencyTabSelected = onPayoutFrequencyTabSelected,
            )
            Box(
                modifier =
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(width = 136.dp, height = 5.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.streamHostHandle),
            )
        }
    }
}

@Composable
private fun ViewerSettingsHeader(onDismiss: () -> Unit) {
    val colors = AlgoKitTheme.colors

    Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            verticalArrangement = Arrangement.spacedBy(5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Viewer Settings",
                fontSize = 28.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Bold,
                color = colors.streamHostTitle,
            )
            Text(
                text = "Manage your stream",
                fontSize = 14.sp,
                color = colors.streamHostSecondaryText,
            )
        }

        StreamSettingsTopSquareIconButton(
            icon = Res.drawable.ic_cross,
            onClick = onDismiss,
            modifier = Modifier.align(Alignment.TopEnd),
            cornerRadius = 12.dp,
        )
    }
}

@Composable
private fun RelayerCard(
    enabled: Boolean,
    onEnabledChanged: (Boolean) -> Unit,
) {
    val colors = AlgoKitTheme.colors

    Surface(shape = RoundedCornerShape(16.dp), color = colors.streamHostCardBackground) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LiquidRingToggle(
                    checked = enabled,
                    onCheckedChange = onEnabledChanged,
                )
                Text(
                    text = "Willing to be a Relayer?",
                    fontSize = 30.sp / 1.9f,
                    color = colors.streamHostCardHeading,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(modifier = Modifier.height(5.dp))
            Text(
                text =
                    "When you relay the stream, you can earn a percentage of transaction fees " +
                        "to subsidize your viewing costs. Requires stable network & battery.",
                fontSize = 12.sp,
                color = colors.streamHostCardBody,
                lineHeight = 18.sp,
            )
        }
    }
}

@Composable
private fun ViewerPayoutFrequencyBlock(
    selectedTabId: String,
    onPayoutFrequencyTabSelected: (String) -> Unit,
) {
    StreamSettingsPayoutFrequencyBlock(
        selectedTabId = selectedTabId,
        onPayoutFrequencyTabSelected = onPayoutFrequencyTabSelected,
        footnoteText = "Batching payouts every 256 blocks saves you ~ $4,000 USD/year in transaction fees compared to every block.",
        footnoteStarTopPadding = 0.dp,
    )
}

@Preview
@Composable
private fun StreamViewerSettingsSheetLightPreview() {
    AlgoKitTheme {
        CompositionLocalProvider(
            LocalThemeIsDark provides mutableStateOf(false),
            LocalCustomColors provides AlgoKitLightColor,
        ) {
            StreamViewerSettingsSheet(
                selectedPayoutFrequencyTabId = PAYOUT_EVERY_256_BLOCKS_TAB_ID,
                willingToBeRelayerEnabled = true,
                realTimeRate = "0.42",
                streamRevenue = "+1.402.15",
                securedViaLabel = "Secured via Algorand Mainnet",
                blockNumberLabel = "#38291041",
                onPayoutFrequencyTabSelected = {},
                onWillingToBeRelayerChanged = {},
                onDismiss = {},
            )
        }
    }
}

@Preview
@Composable
private fun StreamViewerSettingsSheetDarkPreview() {
    AlgoKitTheme {
        CompositionLocalProvider(
            LocalThemeIsDark provides mutableStateOf(true),
            LocalCustomColors provides AlgoKitDarkColor,
        ) {
            StreamViewerSettingsSheet(
                selectedPayoutFrequencyTabId = PAYOUT_EVERY_256_BLOCKS_TAB_ID,
                willingToBeRelayerEnabled = true,
                realTimeRate = "0.42",
                streamRevenue = "+1.402.15",
                securedViaLabel = "Secured via Algorand Mainnet",
                blockNumberLabel = "#38291041",
                onPayoutFrequencyTabSelected = {},
                onWillingToBeRelayerChanged = {},
                onDismiss = {},
            )
        }
    }
}

@Preview
@Composable
private fun StreamViewerSettingsSheetRelayerOffLightPreview() {
    AlgoKitTheme {
        CompositionLocalProvider(
            LocalThemeIsDark provides mutableStateOf(false),
            LocalCustomColors provides AlgoKitLightColor,
        ) {
            StreamViewerSettingsSheet(
                selectedPayoutFrequencyTabId = PAYOUT_EVERY_256_BLOCKS_TAB_ID,
                willingToBeRelayerEnabled = false,
                realTimeRate = "0.42",
                streamRevenue = "+1.402.15",
                securedViaLabel = "Secured via Algorand Mainnet",
                blockNumberLabel = "#38291041",
                onPayoutFrequencyTabSelected = {},
                onWillingToBeRelayerChanged = {},
                onDismiss = {},
            )
        }
    }
}

@Preview
@Composable
private fun StreamViewerSettingsSheetRelayerOffDarkPreview() {
    AlgoKitTheme {
        CompositionLocalProvider(
            LocalThemeIsDark provides mutableStateOf(true),
            LocalCustomColors provides AlgoKitDarkColor,
        ) {
            StreamViewerSettingsSheet(
                selectedPayoutFrequencyTabId = PAYOUT_EVERY_256_BLOCKS_TAB_ID,
                willingToBeRelayerEnabled = false,
                realTimeRate = "0.42",
                streamRevenue = "+1.402.15",
                securedViaLabel = "Secured via Algorand Mainnet",
                blockNumberLabel = "#38291041",
                onPayoutFrequencyTabSelected = {},
                onWillingToBeRelayerChanged = {},
                onDismiss = {},
            )
        }
    }
}
