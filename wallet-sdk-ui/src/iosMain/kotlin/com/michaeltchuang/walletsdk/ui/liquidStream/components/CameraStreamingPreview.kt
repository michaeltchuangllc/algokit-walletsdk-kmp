package com.michaeltchuang.walletsdk.ui.liquidStream.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.UIKitView
import com.michaeltchuang.walletsdk.ui.liquidAuth.service.LiquidAuthConnectionManager
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceDiscoverySession
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureDevicePositionBack
import platform.AVFoundation.AVCaptureDevicePositionFront
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInWideAngleCamera
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureSessionPreset640x480
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIView
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_get_global_queue
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT

/**
 * iOS actual implementation of camera streaming preview.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun createCameraStreamingPreview(
    connectionManager: LiquidAuthConnectionManager?,
    controller: CameraStreamingPreviewController?,
): @Composable () -> Unit =
    {
        IOSCameraPreviewContent(
            connectionManager = connectionManager,
            controller = controller,
        )
    }

@OptIn(ExperimentalForeignApi::class)
@Composable
private fun IOSCameraPreviewContent(
    connectionManager: LiquidAuthConnectionManager?,
    controller: CameraStreamingPreviewController?,
) {
    val session =
        remember {
            AVCaptureSession().apply {
                sessionPreset = AVCaptureSessionPreset640x480
            }
        }
    val previewView = remember { CameraPreviewContainerView(session) }
    var cameraPosition by remember { mutableStateOf(AVCaptureDevicePositionBack) }
    val canSwitchCamera = remember { hasCamera(AVCaptureDevicePositionBack) && hasCamera(AVCaptureDevicePositionFront) }
    var hasActiveInput by remember { mutableStateOf(false) }

    DisposableEffect(cameraPosition) {
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
            val configured = configureSessionInput(session, cameraPosition)
            dispatch_async(dispatch_get_main_queue()) {
                hasActiveInput = configured
            }
            if (configured && !session.running) {
                session.startRunning()
            }
        }
        onDispose {
            // Keep session alive during lens switch; stop on final dispose below.
        }
    }

    DisposableEffect(session) {
        onDispose {
            dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
                if (session.running) {
                    session.stopRunning()
                }
            }
        }
    }

    SideEffect {
        controller?.onRotateCamera = {
            if (canSwitchCamera) {
                cameraPosition =
                    if (cameraPosition == AVCaptureDevicePositionBack) {
                        AVCaptureDevicePositionFront
                    } else {
                        AVCaptureDevicePositionBack
                    }
            }
        }
    }

    DisposableEffect(controller) {
        onDispose {
            controller?.onRotateCamera = null
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        UIKitView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )
        if (!hasActiveInput) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Unable to start camera",
                    color = Color.White,
                )
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private class CameraPreviewContainerView(
    session: AVCaptureSession,
) : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    private val previewLayer =
        AVCaptureVideoPreviewLayer.layerWithSession(session).apply {
            videoGravity = AVLayerVideoGravityResizeAspectFill
        }

    init {
        layer.addSublayer(previewLayer)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        previewLayer.frame = bounds
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun hasCamera(position: Long): Boolean {
    val discoverySession =
        AVCaptureDeviceDiscoverySession.discoverySessionWithDeviceTypes(
            deviceTypes = listOf(AVCaptureDeviceTypeBuiltInWideAngleCamera),
            mediaType = AVMediaTypeVideo,
            position = position,
        )
    return discoverySession.devices.isNotEmpty()
}

@OptIn(ExperimentalForeignApi::class)
private fun configureSessionInput(
    session: AVCaptureSession,
    position: Long,
): Boolean {
    val discoverySession =
        AVCaptureDeviceDiscoverySession.discoverySessionWithDeviceTypes(
            deviceTypes = listOf(AVCaptureDeviceTypeBuiltInWideAngleCamera),
            mediaType = AVMediaTypeVideo,
            position = position,
        )
    val device = discoverySession.devices.firstOrNull() as? AVCaptureDevice ?: return false
    val input = AVCaptureDeviceInput.deviceInputWithDevice(device, error = null) ?: return false

    session.beginConfiguration()
    session.inputs.forEach { session.removeInput(it as platform.AVFoundation.AVCaptureInput) }
    if (!session.canAddInput(input)) {
        session.commitConfiguration()
        return false
    }
    session.addInput(input)
    session.commitConfiguration()
    return true
}
