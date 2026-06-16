package com.michaeltchuang.walletsdk.ui.liquidStream.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import org.jetbrains.skia.Image

@Composable
actual fun VideoFrameDisplay(
    frameData: ByteArray,
    aspectRatio: Float,
) {
    val imageBitmap: ImageBitmap? =
        remember(frameData) {
            if (frameData.isEmpty()) return@remember null
            runCatching {
                Image.makeFromEncoded(frameData).toComposeImageBitmap()
            }.getOrElse { e ->
                println("VideoFrameDisplay: decode failed (${frameData.size} B): $e")
                null
            }
        }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (imageBitmap != null) {
            Image(
                bitmap = imageBitmap,
                contentDescription = "Video frame",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                text = if (frameData.isEmpty()) "Waiting for stream…" else "Failed to decode frame",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
