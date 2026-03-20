package com.michaeltchuang.walletsdk.ui.liquidAuth.components

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cached
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
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
        var hasCameraPermission by
            remember {
                mutableStateOf(
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.CAMERA,
                    ) == PackageManager.PERMISSION_GRANTED,
                )
            }
        val cameraPermissionLauncher =
            rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
            ) { isGranted ->
                hasCameraPermission = isGranted
            }
        LaunchedEffect(hasCameraPermission) {
            if (!hasCameraPermission) {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
        LaunchedEffect(context) {
            hasCameraPermission =
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA,
                ) == PackageManager.PERMISSION_GRANTED
        }

        // Check camera permission
        // Request camera permission at runtime when needed

        if (!hasCameraPermission) {
            // Show permission required UI
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(AlgoKitTheme.colors.background)
                        .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "Camera Permission Required",
                        color = AlgoKitTheme.colors.textMain,
                        style = AlgoKitTheme.typography.title.regular.sansMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Please grant camera permission in:\nSettings → Apps → WalletSDK Demo → Permissions → Camera",
                        color = AlgoKitTheme.colors.textGray,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                    ) {
                        Text(text = "Grant Camera Permission")
                    }
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
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var canSwitchCamera by remember { mutableStateOf(false) }
    var iconRotationTarget by remember { mutableFloatStateOf(0f) }
    val iconRotation by
        animateFloatAsState(
            targetValue = iconRotationTarget,
            animationSpec = tween(durationMillis = 250),
            label = "cameraSwitchIconRotation",
        )
    val frameIntervalMs = 100L
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }

    LaunchedEffect(Unit) {
        isStreaming = true
        Log.d("CameraStreaming", "Streaming started with permission")
    }

    DisposableEffect(lifecycleOwner, lensFacing) {
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
                    analyzerExecutor,
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

                val backCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                val frontCameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
                val hasBackCamera = cameraProvider.hasCamera(backCameraSelector)
                val hasFrontCamera = cameraProvider.hasCamera(frontCameraSelector)
                canSwitchCamera = hasBackCamera && hasFrontCamera
                val requestedSelector =
                    if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                        frontCameraSelector
                    } else {
                        backCameraSelector
                    }
                val cameraSelector =
                    when {
                        cameraProvider.hasCamera(requestedSelector) -> requestedSelector
                        hasBackCamera -> backCameraSelector
                        hasFrontCamera -> frontCameraSelector
                        else -> null
                    }
                if (cameraSelector == null) {
                    Log.e("CameraStreaming", "No available camera found on device")
                    return@addListener
                }
                val resolvedLensFacing =
                    if (cameraSelector == frontCameraSelector) {
                        CameraSelector.LENS_FACING_FRONT
                    } else {
                        CameraSelector.LENS_FACING_BACK
                    }
                if (resolvedLensFacing != lensFacing) {
                    lensFacing = resolvedLensFacing
                }
                Log.d(
                    "CameraStreaming",
                    "Using ${if (resolvedLensFacing == CameraSelector.LENS_FACING_FRONT) "front" else "back"} camera",
                )

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
    DisposableEffect(Unit) {
        onDispose {
            isStreaming = false
            analyzerExecutor.shutdown()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )
        if (canSwitchCamera) {
            IconButton(
                onClick = {
                    lensFacing =
                        if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                            CameraSelector.LENS_FACING_FRONT
                        } else {
                            CameraSelector.LENS_FACING_BACK
                        }
                    iconRotationTarget += 180f
                },
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
            ) {
                Icon(
                    modifier =
                        Modifier
                            .size(32.dp)
                            .graphicsLayer { rotationZ = iconRotation },
                    imageVector = Icons.Default.Cached,
                    tint = Color.White,
                    contentDescription = "Switch camera",
                )
            }
        }
    }
}

@OptIn(ExperimentalGetImage::class)
private fun processAndSendFrame(
    imageProxy: ImageProxy,
    connectionManager: LiquidAuthConnectionManager,
) {
    try {
        val image = imageProxy.image ?: return
        val encodedFrame =
            yuvToJpeg(
                image = image,
                quality = 70,
                rotationDegrees = imageProxy.imageInfo.rotationDegrees,
            )
        val jpegBytes = encodedFrame?.bytes ?: return
        val frameWidth = encodedFrame.width
        val frameHeight = encodedFrame.height

        connectionManager.sendVideoFrame(
            frameId = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            frameData = jpegBytes,
            width = frameWidth,
            height = frameHeight,
            format = "jpeg",
        )
    } catch (e: Exception) {
        Log.e("CameraStreaming", "Error processing frame: $e")
    }
}

private fun yuvToJpeg(
    image: Image,
    quality: Int,
    rotationDegrees: Int = 0,
): EncodedFrame? =
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
        val jpegBytes = outputStream.toByteArray()
        if (rotationDegrees == 0) {
            EncodedFrame(
                bytes = jpegBytes,
                width = image.width,
                height = image.height,
            )
        } else {
            rotateJpegFrame(
                jpegBytes = jpegBytes,
                quality = quality,
                rotationDegrees = rotationDegrees,
            )
        }
    } catch (e: Exception) {
        Log.e("CameraStreaming", "Error converting YUV to JPEG: $e")
        null
    }

private data class EncodedFrame(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
)

private fun rotateJpegFrame(
    jpegBytes: ByteArray,
    quality: Int,
    rotationDegrees: Int,
): EncodedFrame? =
    try {
        val sourceBitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return null
        val rotationMatrix =
            Matrix().apply {
                postRotate(rotationDegrees.toFloat())
            }
        val rotatedBitmap =
            Bitmap.createBitmap(
                sourceBitmap,
                0,
                0,
                sourceBitmap.width,
                sourceBitmap.height,
                rotationMatrix,
                true,
            )
        val outputStream = ByteArrayOutputStream()
        rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        EncodedFrame(
            bytes = outputStream.toByteArray(),
            width = rotatedBitmap.width,
            height = rotatedBitmap.height,
        )
    } catch (e: Exception) {
        Log.e("CameraStreaming", "Error rotating JPEG frame: $e")
        null
    } finally {
        // no-op
    }
