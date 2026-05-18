package com.michaeltchuang.walletsdk.ui.liquidStream.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.michaeltchuang.walletsdk.ui.liquidAuth.service.LiquidAuthConnectionManager

/**
 * iOS actual implementation of camera streaming preview.
 *
 * NOTE: This is a placeholder. iOS implementation requires:
 * - AVFoundation for camera capture
 * - Platform-specific bindings to capture frames
 * - Integration with iosDemoApp's LiquidAuthService.swift
 *
 * To implement:
 * 1. Use AVCaptureSession to capture frames
 * 2. Convert CMSampleBuffer to JPEG
 * 3. Send via connectionManager.sendVideoFrame()
 */
actual fun createCameraStreamingPreview(
    connectionManager: LiquidAuthConnectionManager?,
    controller: CameraStreamingPreviewController?,
): @Composable () -> Unit =
    {
        Text("Camera streaming not yet implemented on iOS")
    }
