package com.michaeltchuang.walletsdk.ui.liquidStream.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.liquidAuth.service.LiquidAuthConnectionManager
import kotlinx.coroutines.delay
import org.webrtc.VideoTrack

@Composable
actual fun rememberStandaloneCameraPreview(): @Composable () -> Unit =
    {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current

        fun hasPermission(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

        var hasCameraPermission by remember { mutableStateOf(hasPermission(Manifest.permission.CAMERA)) }

        val permissionLauncher =
            rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
            ) { isGranted ->
                hasCameraPermission = isGranted
            }

        LaunchedEffect(Unit) {
            hasCameraPermission = hasPermission(Manifest.permission.CAMERA)
            if (!hasCameraPermission) {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        if (!hasCameraPermission) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("Grant Camera Permission")
                }
            }
        } else {
            val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    cameraProviderFuture.addListener(
                        {
                            val cameraProvider = cameraProviderFuture.get()
                            val preview =
                                Preview.Builder().build().also {
                                    it.surfaceProvider = previewView.surfaceProvider
                                }

                            try {
                                cameraProvider.unbindAll()
                                cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                )
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        },
                        ContextCompat.getMainExecutor(ctx),
                    )
                    previewView
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

actual fun createCameraStreamingPreview(
    connectionManager: LiquidAuthConnectionManager?,
    controller: CameraStreamingPreviewController?,
): @Composable () -> Unit =
    {
        val context = LocalContext.current

        fun hasPermission(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

        var hasCameraPermission by remember { mutableStateOf(hasPermission(Manifest.permission.CAMERA)) }
        var hasAudioPermission by remember { mutableStateOf(hasPermission(Manifest.permission.RECORD_AUDIO)) }

        val permissionLauncher =
            rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions(),
            ) { grants ->
                hasCameraPermission = grants[Manifest.permission.CAMERA] ?: hasCameraPermission
                hasAudioPermission = grants[Manifest.permission.RECORD_AUDIO] ?: hasAudioPermission
            }

        LaunchedEffect(Unit) {
            hasCameraPermission = hasPermission(Manifest.permission.CAMERA)
            hasAudioPermission = hasPermission(Manifest.permission.RECORD_AUDIO)
            if (!hasCameraPermission || !hasAudioPermission) {
                permissionLauncher.launch(
                    arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
                )
            }
        }

        if (!hasCameraPermission) {
            PermissionRequiredContent(
                onGrant = {
                    permissionLauncher.launch(
                        arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
                    )
                },
            )
        } else {
            CameraPreviewContent(
                connectionManager = connectionManager,
                controller = controller,
            )
        }
    }

@Composable
private fun PermissionRequiredContent(onGrant: () -> Unit) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(AlgoKitTheme.colors.background)
                .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Camera & Microphone Permission Required",
                color = AlgoKitTheme.colors.textMain,
                style = AlgoKitTheme.typography.title.regular.sansMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Please grant camera and microphone permissions to broadcast your stream.",
                color = AlgoKitTheme.colors.textGray,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onGrant) {
                Text(text = "Grant Permissions")
            }
        }
    }
}

@Composable
private fun CameraPreviewContent(
    connectionManager: LiquidAuthConnectionManager?,
    controller: CameraStreamingPreviewController?,
) {
    // The local track is created asynchronously by the SignalService once a viewer connects
    // (or as soon as the peer connection is set up), so poll until it becomes available.
    var localVideoTrack by remember { mutableStateOf<VideoTrack?>(null) }
    val eglContext = remember(connectionManager) { connectionManager?.getStreamEglBaseContext() }

    LaunchedEffect(connectionManager) {
        while (connectionManager != null) {
            val track = connectionManager.getLocalVideoTrack()
            if (track !== localVideoTrack) {
                localVideoTrack = track
            }
            delay(300)
        }
    }

    // Wire camera rotation through the controller to the WebRTC capturer.
    controller?.onRotateCamera = { connectionManager?.switchCamera() }
    DisposableEffect(controller) {
        onDispose { controller?.onRotateCamera = null }
    }

    Box(modifier = Modifier.fillMaxSize().background(AlgoKitTheme.colors.background)) {
        WebRtcVideoRenderer(
            eglBaseContext = eglContext ?: connectionManager?.getStreamEglBaseContext(),
            videoTrack = localVideoTrack,
            mirror = true,
        )
    }
}
