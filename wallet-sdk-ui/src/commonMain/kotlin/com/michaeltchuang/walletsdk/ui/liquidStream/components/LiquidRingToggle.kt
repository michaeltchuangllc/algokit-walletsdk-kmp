package com.michaeltchuang.walletsdk.ui.liquidStream.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun LiquidRingToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val trackWidth = 42.dp
    val trackHeight = 22.dp
    val thumbSize = 18.dp

    Box(
        modifier =
            modifier
                .size(width = trackWidth, height = trackHeight)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFFA7C1C7))
                .clickable { onCheckedChange(!checked) },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .align(if (checked) Alignment.CenterStart else Alignment.CenterEnd)
                    .offset(x = if (checked) 2.dp else (-2).dp)
                    .size(8.dp)
                    .border(1.dp, Color.White, CircleShape),
        )

        Box(
            modifier =
                Modifier
                    .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                    .offset(x = if (checked) (-2).dp else 2.dp)
                    .size(thumbSize)
                    .shadow(elevation = 14.dp, shape = CircleShape)
                    .clip(CircleShape)
                    .background(Color.White),
        )
    }
}

@Preview
@Composable
private fun LiquidRingTogglePreview() {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(16.dp),
    ) {
        LiquidRingToggle(checked = false, onCheckedChange = {})
        LiquidRingToggle(checked = true, onCheckedChange = {})
    }
}
