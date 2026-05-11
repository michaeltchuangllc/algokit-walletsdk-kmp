package com.michaeltchuang.walletsdk.ui.liquidStream.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.liquidStream.utils.payoutFrequencyTabs

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
