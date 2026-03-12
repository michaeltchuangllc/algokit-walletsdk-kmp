package com.michaeltchuang.walletsdk.ui.liquidAuth.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * Video Frame Viewer Component
 *
 * Displays a video frame received from WebRTC data channel.
 * Supports JPEG/PNG bitmap rendering.
 *
 * @param frameData The raw JPEG/PNG bytes
 * @param width Frame width (for aspect ratio)
 * @param height Frame height (for aspect ratio)
 * @param isLive Whether this is a live stream (shows indicator)
 * @param modifier Modifier for styling
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
fun VideoFrameViewer(
    frameData: ByteArray,
    width: Int = 640,
    height: Int = 480,
    isLive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val aspectRatio = width.toFloat() / height.toFloat()

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = AlgoKitTheme.colors.layerGray,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Live indicator
            if (isLive) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                ) {
                    androidx.compose.foundation.layout.Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.align(Alignment.TopStart),
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .background(Color.Red, RoundedCornerShape(50))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = "LIVE",
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }

                    Text(
                        text = "Camera Feed",
                        style = MaterialTheme.typography.labelMedium,
                        color = AlgoKitTheme.colors.textGray,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
            }

            // Video frame display
            VideoFrameDisplay(
                frameData = frameData,
                aspectRatio = aspectRatio,
            )

            // Frame info
            Text(
                text = "${width}x${height} • ${frameData.size / 1024}KB",
                style = MaterialTheme.typography.labelSmall,
                color = AlgoKitTheme.colors.textGray,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/**
 * Platform-specific video frame display
 */
@Composable
expect fun VideoFrameDisplay(
    frameData: ByteArray,
    aspectRatio: Float,
)

/**
 * Video Stream Placeholder (shown when no frames yet)
 */
@Composable
fun VideoStreamPlaceholder(
    isWaiting: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .aspectRatio(4f / 3f),
        colors = CardDefaults.cardColors(
            containerColor = AlgoKitTheme.colors.layerGray,
        ),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            if (isWaiting) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Waiting for camera feed...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AlgoKitTheme.colors.textGray,
                    )
                }
            } else {
                Text(
                    text = "Camera stream ended",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AlgoKitTheme.colors.textGray,
                )
            }
        }
    }
}

/**
 * Video Stream Container - handles state transitions
 */
@Composable
fun VideoStreamContainer(
    frameData: ByteArray?,
    width: Int = 640,
    height: Int = 480,
    isLive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    when {
        frameData != null -> {
            VideoFrameViewer(
                frameData = frameData,
                width = width,
                height = height,
                isLive = isLive,
                modifier = modifier,
            )
        }
        isLive -> {
            VideoStreamPlaceholder(
                isWaiting = true,
                modifier = modifier,
            )
        }
        else -> {
            VideoStreamPlaceholder(
                isWaiting = false,
                modifier = modifier,
            )
        }
    }
}