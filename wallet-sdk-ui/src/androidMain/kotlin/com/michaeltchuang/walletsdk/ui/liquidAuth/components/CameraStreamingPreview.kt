package com.michaeltchuang.walletsdk.ui.liquidAuth.components

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import com.michaeltchuang.walletsdk.ui.liquidAuth.service.LiquidAuthConnectionManager
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Android actual implementation of camera streaming preview.
 *
 * Uses CameraX to capture frames and stream via WebRTC.
 */
actual fun createCameraStreamingPreview(connectionManager: LiquidAuthConnectionManager?): @Composable () -> Unit =
    {
        val context = LocalContext.current

        // Check camera permission
        val hasCameraPermission =
            remember {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA,
                ) == PackageManager.PERMISSION_GRANTED
            }

        if (!hasCameraPermission) {
            // Show permission required UI
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "Camera Permission Required",
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Please grant camera permission in:\nSettings → Apps → WalletSDK Demo → Permissions → Camera",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            CameraPreviewContent(
                connectionManager = connectionManager,
            )
        }
    }

@Composable
private fun CameraPreviewContent(connectionManager: LiquidAuthConnectionManager?) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val previewView =
        remember {
            PreviewView(context).apply {
                implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
        }

    var isStreaming by remember { mutableStateOf(false) }
    var lastFrameTime by remember { mutableLongStateOf(0L) }
    val frameIntervalMs = 100L

    LaunchedEffect(Unit) {
        isStreaming = true
        Log.d("CameraStreaming", "Streaming started with permission")
    }

    DisposableEffect(lifecycleOwner) {
        Log.d("CameraStreaming", "DisposableEffect triggered, binding camera...")
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                Log.d("CameraStreaming", "CameraProvider obtained")

                val preview =
                    Preview
                        .Builder()
                        .setTargetResolution(android.util.Size(640, 480))
                        .build()
                        .also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                            Log.d("CameraStreaming", "Surface provider set")
                        }

                val imageAnalysis =
                    ImageAnalysis
                        .Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .build()

                imageAnalysis.setAnalyzer(
                    Executors.newSingleThreadExecutor(),
                ) { imageProxy ->
                    if (isStreaming && connectionManager?.isConnected() == true) {
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastFrameTime >= frameIntervalMs) {
                            lastFrameTime = currentTime
                            processAndSendFrame(imageProxy, connectionManager)
                        }
                    }
                    imageProxy.close()
                }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                Log.d("CameraStreaming", "Using back camera")

                cameraProvider.unbindAll()
                Log.d("CameraStreaming", "Unbound previous use cases")

                val camera =
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalysis,
                    )
                Log.d("CameraStreaming", "Camera bound successfully: ${camera.cameraInfo}")
            } catch (e: Exception) {
                Log.e("CameraStreaming", "Failed to start camera: $e", e)
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            isStreaming = false
            Log.d("CameraStreaming", "Disposing camera...")
            try {
                val cameraProvider = ProcessCameraProvider.getInstance(context).get()
                cameraProvider.unbindAll()
                Log.d("CameraStreaming", "Camera unbound successfully")
            } catch (e: Exception) {
                Log.e("CameraStreaming", "Error stopping camera: $e")
            }
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize(),
    )
}

@OptIn(ExperimentalGetImage::class)
private fun processAndSendFrame(
    imageProxy: ImageProxy,
    connectionManager: LiquidAuthConnectionManager,
) {
    try {
        val image = imageProxy.image ?: return
        val jpegBytes = yuvToJpeg(image, 70)

        if (jpegBytes != null) {
            connectionManager.sendVideoFrame(
                frameId = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                frameData = jpegBytes,
                width = imageProxy.width,
                height = imageProxy.height,
                format = "jpeg",
            )
        }
    } catch (e: Exception) {
        Log.e("CameraStreaming", "Error processing frame: $e")
    }
}

private fun yuvToJpeg(
    image: Image,
    quality: Int,
): ByteArray? =
    try {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage =
            YuvImage(
                nv21,
                ImageFormat.NV21,
                image.width,
                image.height,
                null,
            )

        val outputStream = ByteArrayOutputStream()
        yuvImage.compressToJpeg(
            Rect(0, 0, image.width, image.height),
            quality,
            outputStream,
        )

        outputStream.toByteArray()
    } catch (e: Exception) {
        Log.e("CameraStreaming", "Error converting YUV to JPEG: $e")
        null
    }
