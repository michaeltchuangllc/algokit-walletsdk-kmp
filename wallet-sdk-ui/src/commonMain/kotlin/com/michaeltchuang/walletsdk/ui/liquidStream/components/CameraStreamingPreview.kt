package com.michaeltchuang.walletsdk.ui.liquidStream.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.michaeltchuang.walletsdk.ui.liquidAuth.service.LiquidAuthConnectionManager

/**
 * Factory function to create a camera preview composable for video streaming.
 *
 * Platform implementations render the native WebRTC video track and display a camera preview.
 *
 * @return A composable that displays the camera preview
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

@Composable
expect fun rememberStandaloneCameraPreview(): @Composable () -> Unit
