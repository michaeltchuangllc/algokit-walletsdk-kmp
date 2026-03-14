package com.michaeltchuang.walletsdk.ui.liquidAuth.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/**
 * Android implementation of video frame display.
 * Decodes JPEG/PNG bytes to Bitmap and renders with Compose Image.
 */
@Composable
actual fun VideoFrameDisplay(
    frameData: ByteArray,
    aspectRatio: Float,
) {
    val bitmap =
        remember(frameData) {
            try {
                BitmapFactory.decodeByteArray(frameData, 0, frameData.size)
            } catch (e: Exception) {
                null
            }
        }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .aspectRatio(aspectRatio)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Video frame",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else {
            Text(
                text = "Failed to decode frame",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
