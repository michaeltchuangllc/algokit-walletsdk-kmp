package com.michaeltchuang.walletsdk.ui.liquidStream.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitView
import com.michaeltchuang.walletsdk.ui.liquidAuth.service.LiquidAuthConnectionManager
import com.michaeltchuang.walletsdk.ui.liquidAuth.service.iosBroadcastVideoViewProvider
import io.github.aakira.napier.Napier
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusDenied
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVAuthorizationStatusRestricted
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureDevicePositionBack
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.position
import platform.AVFoundation.requestAccessForMediaType
import platform.Foundation.NSData
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.QuartzCore.CALayer
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UIKit.UIView
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.NSObjectProtocol
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "StandaloneCameraPreview"

/** Returns the back-facing camera when available, matching Android's `CameraSelector.DEFAULT_BACK_CAMERA`. */
@OptIn(ExperimentalForeignApi::class)
private fun backCameraOrDefault(): AVCaptureDevice? {
    val devices = AVCaptureDevice.devicesWithMediaType(AVMediaTypeVideo) as? List<AVCaptureDevice>
    Napier.d("$TAG: discovered ${devices?.size ?: 0} video capture device(s)", tag = TAG)
    return devices?.firstOrNull { it.position == AVCaptureDevicePositionBack }
        ?: AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)
}

/** Drives the placeholder/preview UI while the camera session is being set up. */
private enum class CameraPreviewStatus {
    CHECKING_PERMISSION,
    READY,
    NO_DEVICE,
    PERMISSION_DENIED,
}

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberStandaloneCameraPreview(): @Composable () -> Unit =
    {
        val session = remember { AVCaptureSession() }
        var status by remember { mutableStateOf(CameraPreviewStatus.CHECKING_PERMISSION) }

        val cameraView =
            remember {
                UIView().apply {
                    val previewLayer = AVCaptureVideoPreviewLayer.layerWithSession(session)
                    previewLayer.videoGravity = AVLayerVideoGravityResizeAspectFill
                    layer.addSublayer(previewLayer)
                }
            }

        fun configureSessionAndStart() {
            val device = backCameraOrDefault()
            if (device == null) {
                // Most common on the iOS Simulator, which has no camera hardware at all.
                Napier.w("$TAG: no camera device available (Simulator has no camera hardware)", tag = TAG)
                status = CameraPreviewStatus.NO_DEVICE
                return
            }
            val input = AVCaptureDeviceInput.deviceInputWithDevice(device, null)
            if (input == null || !session.canAddInput(input)) {
                Napier.w("$TAG: failed to create/add capture input for device=${device.localizedName}", tag = TAG)
                status = CameraPreviewStatus.NO_DEVICE
                return
            }
            session.addInput(input)
            status = CameraPreviewStatus.READY
            Napier.d("$TAG: session configured with device=${device.localizedName}, starting session", tag = TAG)
            // Per Apple guidance, startRunning() is a blocking call and should not be executed
            // on the main thread.
            dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0uL)) {
                session.startRunning()
                Napier.d("$TAG: session.isRunning=${session.isRunning()}", tag = TAG)
            }
        }

        fun checkPermissionAndConfigure() {
            val authStatus = AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)
            Napier.d("$TAG: authorizationStatus=$authStatus", tag = TAG)
            when (authStatus) {
                AVAuthorizationStatusAuthorized -> {
                    // Avoid re-configuring/re-adding an input if we're already up and running
                    // (e.g. this can be re-triggered when the app returns to the foreground).
                    if (status != CameraPreviewStatus.READY) configureSessionAndStart()
                }
                AVAuthorizationStatusNotDetermined -> {
                    AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted ->
                        dispatch_async(dispatch_get_main_queue()) {
                            Napier.d("$TAG: requestAccessForMediaType granted=$granted", tag = TAG)
                            if (granted) {
                                configureSessionAndStart()
                            } else {
                                status = CameraPreviewStatus.PERMISSION_DENIED
                            }
                        }
                    }
                }
                AVAuthorizationStatusDenied, AVAuthorizationStatusRestricted -> {
                    // Permission was previously denied - iOS will NOT show the system prompt
                    // again. The user must enable it manually in Settings > Privacy > Camera.
                    Napier.w("$TAG: camera permission denied/restricted (status=$authStatus)", tag = TAG)
                    status = CameraPreviewStatus.PERMISSION_DENIED
                }
                else -> {
                    Napier.w("$TAG: unexpected authorizationStatus=$authStatus", tag = TAG)
                    status = CameraPreviewStatus.NO_DEVICE
                }
            }
        }

        LaunchedEffect(Unit) { checkPermissionAndConfigure() }

        DisposableEffect(Unit) {
            // If the user backgrounds the app to flip the permission on in Settings, re-check
            // as soon as they return so the preview can recover without needing a restart.
            val observer: NSObjectProtocol =
                NSNotificationCenter.defaultCenter.addObserverForName(
                    name = UIApplicationDidBecomeActiveNotification,
                    `object` = null,
                    queue = NSOperationQueue.mainQueue,
                ) {
                    if (status == CameraPreviewStatus.PERMISSION_DENIED) {
                        Napier.d("$TAG: app became active, re-checking camera permission", tag = TAG)
                        checkPermissionAndConfigure()
                    }
                }

            onDispose {
                NSNotificationCenter.defaultCenter.removeObserver(observer)
                if (session.isRunning()) {
                    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0uL)) {
                        session.stopRunning()
                    }
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            when (status) {
                CameraPreviewStatus.READY -> {
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
                CameraPreviewStatus.CHECKING_PERMISSION -> {
                    Text(text = "Requesting camera access\u2026", color = Color.White)
                }
                CameraPreviewStatus.PERMISSION_DENIED -> {
                    Text(
                        text = "Camera access denied.\nTap to open Settings and enable it.",
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier =
                            Modifier
                                .padding(24.dp)
                                .clickable {
                                    val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString)
                                    if (url != null) {
                                        UIApplication.sharedApplication.openURL(
                                            url = url,
                                            options = emptyMap<Any?, Any>(),
                                            completionHandler = { success ->
                                                Napier.d("$TAG: openURL(Settings) success=$success", tag = TAG)
                                            },
                                        )
                                    }
                                },
                    )
                }
                CameraPreviewStatus.NO_DEVICE -> {
                    Text(text = "Camera unavailable (no device found)", color = Color.White)
                }
            }
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
