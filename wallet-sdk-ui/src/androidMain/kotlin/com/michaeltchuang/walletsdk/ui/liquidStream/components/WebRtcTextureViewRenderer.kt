package com.michaeltchuang.walletsdk.ui.liquidStream.components

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.TextureView
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.util.concurrent.CountDownLatch

class WebRtcTextureViewRenderer(
    context: Context,
) : TextureView(context),
    VideoSink,
    TextureView.SurfaceTextureListener {
    private val eglRenderer = EglRenderer("WebRtcTextureViewRenderer")

    private var rotatedFrameWidth = 0
    private var rotatedFrameHeight = 0
    private var initialized = false

    init {
        surfaceTextureListener = this
    }

    /** Initialize the renderer with a shared [EglBase.Context] (must match the decoder's). */
    fun init(sharedContext: EglBase.Context?) {
        if (initialized) return
        initialized = true
        eglRenderer.init(sharedContext, EglBase.CONFIG_PLAIN, GlRectDrawer())
        // Re-attach if the surface texture is already available.
        surfaceTexture?.let { eglRenderer.createEglSurface(it) }
    }

    fun setMirror(mirror: Boolean) {
        eglRenderer.setMirror(mirror)
    }

    fun release() {
        if (!initialized) return
        initialized = false
        eglRenderer.release()
    }

    override fun onFrame(frame: VideoFrame) {
        updateFrameDimensions(frame)
        eglRenderer.onFrame(frame)
    }

    private fun updateFrameDimensions(frame: VideoFrame) {
        val rotation = frame.rotation
        val width: Int
        val height: Int
        if (rotation == 90 || rotation == 270) {
            width = frame.buffer.height
            height = frame.buffer.width
        } else {
            width = frame.buffer.width
            height = frame.buffer.height
        }
        if (width != rotatedFrameWidth || height != rotatedFrameHeight) {
            rotatedFrameWidth = width
            rotatedFrameHeight = height
            post {
                if (height > 0) {
                    eglRenderer.setLayoutAspectRatio(width.toFloat() / height)
                }
            }
        }
    }

    override fun onSurfaceTextureAvailable(
        surface: SurfaceTexture,
        width: Int,
        height: Int,
    ) {
        if (initialized) {
            eglRenderer.createEglSurface(surface)
        }
    }

    override fun onSurfaceTextureSizeChanged(
        surface: SurfaceTexture,
        width: Int,
        height: Int,
    ) {
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        val completionLatch = CountDownLatch(1)
        eglRenderer.releaseEglSurface { completionLatch.countDown() }
        runCatching { completionLatch.await() }
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
}
