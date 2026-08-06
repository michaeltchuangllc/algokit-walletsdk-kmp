package com.michaeltchuang.walletsdk.ui.liquidStream.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.UIKitView
import com.michaeltchuang.walletsdk.ui.liquidAuth.service.LiquidAuthConnectionManager
import com.michaeltchuang.walletsdk.ui.liquidAuth.service.iosBroadcastVideoViewProvider
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.Foundation.NSData
import platform.QuartzCore.CALayer
import platform.UIKit.UIView

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberStandaloneCameraPreview(): @Composable () -> Unit =
    {
        val cameraView =
            remember {
                UIView().apply {
                    val session = AVCaptureSession()
                    val device = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)
                    if (device != null) {
                        val input = AVCaptureDeviceInput.deviceInputWithDevice(device, null)
                        if (input != null && session.canAddInput(input)) {
                            session.addInput(input)
                            val previewLayer = AVCaptureVideoPreviewLayer.layerWithSession(session)
                            previewLayer.videoGravity = AVLayerVideoGravityResizeAspectFill
                            layer.addSublayer(previewLayer)
                            session.startRunning()
                        }
                    }
                }
            }

        UIKitView(
            factory = { cameraView },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                view.layer.sublayers?.firstOrNull()?.let { layer ->
                    if (layer is CALayer) {
                        layer.frame = view.bounds
                    }
                }
            },
        )
    }

/** Legacy JPEG capture bridge retained for source compatibility; native WebRTC tracks are used instead. */
var iosBroadcastCaptureSession: AVCaptureSession? = null
var iosOnBroadcastFrameReady: ((data: NSData, width: Int, height: Int) -> Unit)? = null
var iosStartBroadcastFrameCapture: (() -> Unit)? = null
var iosStopBroadcastFrameCapture: (() -> Unit)? = null

/**
 * iOS host preview backed by the same native WebRTC camera track that is sent to the viewer.
 * This avoids opening a competing AVCaptureSession and eliminates JPEG frame transport.
 */
actual fun createCameraStreamingPreview(
    connectionManager: LiquidAuthConnectionManager?,
    controller: CameraStreamingPreviewController?,
): @Composable () -> Unit =
    {
        IOSWebRtcCameraPreview(controller)
    }

@Composable
private fun IOSWebRtcCameraPreview(controller: CameraStreamingPreviewController?) {
    var videoView by remember { mutableStateOf<UIView?>(null) }

    LaunchedEffect(Unit) {
        while (videoView == null) {
            videoView = iosBroadcastVideoViewProvider?.invoke() as? UIView
            if (videoView == null) delay(300)
        }
    }

    controller?.onRotateCamera = null

    val renderer = videoView
    if (renderer != null) {
        UIKitView(
            factory = { renderer },
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "Waiting for camera", color = Color.White)
        }
    }
}
