package com.michaeltchuang.walletsdk.ui.liquidStream.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.EglBase
import org.webrtc.VideoTrack

@Composable
fun WebRtcVideoRenderer(
    eglBaseContext: EglBase.Context?,
    videoTrack: VideoTrack?,
    modifier: Modifier = Modifier,
    mirror: Boolean = false,
) {
    if (eglBaseContext == null) return

    val context = LocalContext.current
    val renderer =
        remember(context, eglBaseContext) {
            WebRtcTextureViewRenderer(context)
        }

    // Initialize / release the renderer with the EGL context.
    DisposableEffect(renderer, eglBaseContext) {
        runCatching { renderer.init(eglBaseContext) }
        onDispose {
            runCatching { renderer.release() }
        }
    }

    // Attach / detach the current track's sink.
    DisposableEffect(renderer, videoTrack) {
        renderer.setMirror(mirror)
        runCatching { videoTrack?.addSink(renderer) }
        onDispose {
            runCatching { videoTrack?.removeSink(renderer) }
        }
    }

    AndroidView(
        factory = { renderer },
        modifier = modifier.fillMaxSize().background(Color.Black),
        update = { it.setMirror(mirror) },
    )
}
