package com.michaeltchuang.walletsdk.ui.liquidAuth.components

import androidx.compose.runtime.Composable
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
 * @param connectionManager The connection manager to send video frames through
 * @return A composable that displays camera preview and streams frames
 */
expect fun createCameraStreamingPreview(
    connectionManager: LiquidAuthConnectionManager?,
): @Composable () -> Unit