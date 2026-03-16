package com.michaeltchuang.walletsdk.ui.liquidAuth.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * iOS implementation of video frame display.
 * For iOS, this would need platform-specific UIImage handling.
 * Currently shows placeholder - implement with platform-specific image decoding.
 */
@Composable
actual fun VideoFrameDisplay(
    frameData: ByteArray,
    aspectRatio: Float,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .aspectRatio(aspectRatio)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "iOS video preview not implemented",
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
