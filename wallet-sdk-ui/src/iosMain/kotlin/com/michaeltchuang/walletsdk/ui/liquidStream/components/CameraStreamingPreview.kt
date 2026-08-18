package com.michaeltchuang.walletsdk.ui.liquidStream.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import io.github.aakira.napier.Napier
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.delay
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureDevicePositionBack
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureVideoOrientationPortrait
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.position
import platform.AVFoundation.requestAccessForMediaType
import platform.Foundation.NSData
import platform.QuartzCore.CATransaction
import platform.UIKit.UIScreen
import platform.UIKit.UIView
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberStandaloneCameraPreview(): @Composable () -> Unit =
    {
        var hasPermission by remember {
            mutableStateOf(
                AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo) == AVAuthorizationStatusAuthorized,
            )
        }

        LaunchedEffect(Unit) {
            val status = AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)
            if (status == AVAuthorizationStatusNotDetermined) {
                AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted ->
                    hasPermission = granted
                }
            } else {
                hasPermission = status == AVAuthorizationStatusAuthorized
            }
        }

        if (!hasPermission) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "Camera permission required", color = Color.White)
            }
        } else {
            val session = remember { AVCaptureSession() }
            val previewLayer = remember { AVCaptureVideoPreviewLayer.layerWithSession(session) }

            DisposableEffect(Unit) {
                Napier.d("StandaloneCamera: Initializing session", tag = "CameraPreview")

                val device = AVCaptureDevice.devicesWithMediaType(AVMediaTypeVideo)
                    .mapNotNull { it as? AVCaptureDevice }
                    .firstOrNull { it.position == AVCaptureDevicePositionBack }
                    ?: AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)

                if (device != null) {
                    val input = AVCaptureDeviceInput.deviceInputWithDevice(device, null)
                    if (input != null && session.canAddInput(input)) {
                        session.beginConfiguration()
                        session.addInput(input)
                        previewLayer.videoGravity = AVLayerVideoGravityResizeAspectFill

                        // Explicitly set portrait orientation if possible
                        previewLayer.connection?.let { conn ->
                            if (conn.isVideoOrientationSupported()) {
                                conn.videoOrientation = AVCaptureVideoOrientationPortrait
                            }
                        }

                        session.commitConfiguration()

                        Napier.d("StandaloneCamera: Input and configuration complete (Device: ${device.localizedName})", tag = "CameraPreview")

                        dispatch_async(
                            dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0UL),
                        ) {
                            session.startRunning()
                            Napier.d("StandaloneCamera: session.startRunning() called", tag = "CameraPreview")
                        }
                    } else {
                        Napier.e("StandaloneCamera: Could not add input to session", tag = "CameraPreview")
                    }
                } else {
                    Napier.e("StandaloneCamera: No video device found", tag = "CameraPreview")
                }
                onDispose {
                    Napier.d("StandaloneCamera: Disposing session", tag = "CameraPreview")
                    session.stopRunning()
                }
            }

            UIKitView(
                factory = {
                    val cameraView = UIView()
                    cameraView.backgroundColor = platform.UIKit.UIColor.blackColor
                    cameraView.layer.masksToBounds = true
                    cameraView.layer.addSublayer(previewLayer)

                    // Set a default non-zero frame immediately
                    previewLayer.frame = UIScreen.mainScreen.bounds

                    cameraView
                },
                modifier = Modifier.fillMaxSize(),
                update = { view ->
                    CATransaction.begin()
                    CATransaction.setDisableActions(true)
                    val rect = view.bounds
                    // Only update if bounds are valid
                    if (rect.useContents { size.width > 0 && size.height > 0 }) {
                        previewLayer.frame = rect
                    }
                    CATransaction.commit()
                },
            )
        }
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
            if (videoView == null) delay(300.milliseconds)
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
