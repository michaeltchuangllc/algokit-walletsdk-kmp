package com.michaeltchuang.walletsdk.ui.liquidAuth.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.michaeltchuang.walletsdk.ui.liquidAuth.service.LiquidAuthConnectionManager

/**
 * Factory function to create a camera preview composable for video streaming.
 *
 * The implementation should:
 * 1. Display camera preview
 * 2. Capture frames at regular intervals (e.g., 10-15 FPS)
 * 3. Encode frames to JPEG
 * 4. Call connectionManager.sendVideoFrame() for each frame
 *
 * Example implementation using CameraX:
 * ```kotlin
 * @Composable
 * fun CameraStreamingPreview(
 *     connectionManager: LiquidAuthConnectionManager?,
 * ) {
 *     // Use CameraX Preview and ImageAnalysis
 *     // In ImageAnalysis.Analyzer, convert ImageProxy to JPEG
 *     // Call connectionManager?.sendVideoFrame(frameId, timestamp, jpegBytes, width, height, "jpeg")
 * }
 * ```
 *
 * @return A composable that displays camera preview and streams frames
 */
class CameraStreamingPreviewController {
    var onRotateCamera: (() -> Unit)? by mutableStateOf(null)

    fun rotateCamera() {
        onRotateCamera?.invoke()
    }
}

expect fun createCameraStreamingPreview(
    connectionManager: LiquidAuthConnectionManager?,
    controller: CameraStreamingPreviewController? = null,
): @Composable () -> Unit
