package com.michaeltchuang.walletsdk.ui.liquidAuth.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme

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
