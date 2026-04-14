package com.michaeltchuang.walletsdk.ui.liquidAuth.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.vectorResource

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
