package com.michaeltchuang.walletsdk.core.liquidAuth.auth.connect

import android.content.Context
import android.media.AudioManager
import android.util.Log
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * Owns one factory/EGL context and one camera/microphone capture for a broadcast.
 *
 * The service owns this object, not the peers borrowing it. Destroy all borrowing peers and
 * detach/release renderers before calling [dispose]. Never dispose [factory] or [localVideoTrack]
 * directly. Controls affect every peer using this owner. Creating the owner does not start capture.
 */
class SharedBroadcastMedia(
    context: Context,
) {
    companion object {
        private const val TAG = "connect.SharedMedia"
        private const val LOCAL_VIDEO_TRACK_ID = "local_video"
        private const val LOCAL_AUDIO_TRACK_ID = "local_audio"
        private const val LOCAL_MEDIA_STREAM_ID = "liquid_stream"
        internal const val DEFAULT_CAPTURE_WIDTH = 1280
        internal const val DEFAULT_CAPTURE_HEIGHT = 720
        internal const val DEFAULT_CAPTURE_FPS = 30
    }

    private val appContext = context.applicationContext
    private val eglBase = EglBase.create()
    private var disposed = false

    val eglBaseContext: EglBase.Context get() = eglBase.eglBaseContext
    val factory: PeerConnectionFactory

    /** Borrowed track for preview; the owner alone disposes it. */
    @Volatile
    var localVideoTrack: VideoTrack? = null
        private set
    private var localAudioTrack: AudioTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var captureStarted = false
    private var savedAudioMode: Int? = null
    private var savedSpeakerphoneOn = false

    init {
        try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions
                    .builder(appContext)
                    .setEnableInternalTracer(true)
                    .createInitializationOptions(),
            )
            factory =
                PeerConnectionFactory
                    .builder()
                    .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBaseContext))
                    .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBaseContext, true, true))
                    .setOptions(
                        PeerConnectionFactory.Options().apply {
                            disableEncryption = false
                            disableNetworkMonitor = false
                        },
                    ).createPeerConnectionFactory()
        } catch (error: Throwable) {
            eglBase.release()
            throw error
        }
    }

    /**
     * Starts capture once, independently of any peer. Later calls return the same track and do not
     * change capture dimensions. Returns null for audio-only capture when no camera can be started.
     * The caller must have camera/microphone permissions and the required foreground service.
     */
    @Synchronized
    fun startCapture(
        width: Int = DEFAULT_CAPTURE_WIDTH,
        height: Int = DEFAULT_CAPTURE_HEIGHT,
        fps: Int = DEFAULT_CAPTURE_FPS,
    ): VideoTrack? {
        check(!disposed) { "SharedBroadcastMedia is disposed" }
        if (captureStarted) return localVideoTrack
        require(width > 0 && height > 0 && fps > 0) { "Capture dimensions and fps must be positive" }
        configureAudioForStreaming()

        runCatching {
            val capturer = createCameraCapturer()
            if (capturer != null) {
                videoCapturer = capturer
                val helper = checkNotNull(SurfaceTextureHelper.create("CaptureThread", eglBaseContext))
                surfaceTextureHelper = helper
                val source = factory.createVideoSource(capturer.isScreencast)
                videoSource = source
                capturer.initialize(helper, appContext, source.capturerObserver)
                capturer.startCapture(width, height, fps)
                localVideoTrack = factory.createVideoTrack(LOCAL_VIDEO_TRACK_ID, source)
                localVideoTrack?.setEnabled(true)
            } else {
                Log.w(TAG, "No camera available for local capture")
            }
        }.onFailure {
            Log.e(TAG, "Failed to start camera capture; continuing with audio only", it)
            releaseVideoCapture()
        }

        try {
            val source = factory.createAudioSource(MediaConstraints())
            audioSource = source
            localAudioTrack = factory.createAudioTrack(LOCAL_AUDIO_TRACK_ID, source)
            localAudioTrack?.setEnabled(true)
            captureStarted = true
        } catch (error: Throwable) {
            releaseVideoCapture()
            releaseResource("audio track") { localAudioTrack?.dispose() }
            localAudioTrack = null
            releaseResource("audio source") { audioSource?.dispose() }
            audioSource = null
            releaseResource("audio routing") { restoreAudioMode() }
            throw error
        }
        return localVideoTrack
    }

    /**
     * Attaches existing tracks before SDP negotiation; call [startCapture] first.
     * The peer must come from [factory]. Repeated calls do not add duplicate senders.
     * Does not retain the peer or transfer ownership of the owner's track wrappers.
     * Uses WebRTC's getSenders(), which invalidates previously returned sender wrappers;
     * callers must not cache those wrappers across this call.
     */
    @Synchronized
    fun attachTracks(peerConnection: PeerConnection) {
        check(!disposed) { "SharedBroadcastMedia is disposed" }
        check(captureStarted) { "Call startCapture before attachTracks" }
        val attachedTrackIds = peerConnection.senders.mapNotNull { it.track()?.id() }.toSet()
        listOfNotNull(localVideoTrack, localAudioTrack).forEach { track ->
            if (track.id() !in attachedTrackIds) {
                // addTrack creates a separate sender-owned wrapper/native reference. Disposing
                // the peer releases that reference, not this owner's track or its capture source.
                peerConnection.addTrack(track, listOf(LOCAL_MEDIA_STREAM_ID))
            }
        }
    }

    @Synchronized
    fun switchCamera() {
        check(!disposed) { "SharedBroadcastMedia is disposed" }
        videoCapturer?.switchCamera(null)
    }

    @Synchronized
    fun setAudioEnabled(enabled: Boolean) {
        check(!disposed) { "SharedBroadcastMedia is disposed" }
        localAudioTrack?.setEnabled(enabled)
    }

    @Synchronized
    fun setVideoEnabled(enabled: Boolean) {
        check(!disposed) { "SharedBroadcastMedia is disposed" }
        localVideoTrack?.setEnabled(enabled)
    }

    private fun createCameraCapturer(): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(appContext)
        val name =
            enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
                ?: enumerator.deviceNames.firstOrNull { enumerator.isBackFacing(it) }
                ?: enumerator.deviceNames.firstOrNull()
        return name?.let { enumerator.createCapturer(it, null) }
    }

    /** Routing belongs to the owner so destroying one borrowing peer cannot reset it. */
    @Synchronized
    @Suppress("DEPRECATION")
    internal fun configureAudioForStreaming() {
        check(!disposed) { "SharedBroadcastMedia is disposed" }
        if (savedAudioMode != null) return
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        savedAudioMode = audioManager.mode
        savedSpeakerphoneOn = audioManager.isSpeakerphoneOn
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = true
    }

    @Suppress("DEPRECATION")
    private fun restoreAudioMode() {
        val previousMode = savedAudioMode ?: return
        savedAudioMode = null
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.isSpeakerphoneOn = savedSpeakerphoneOn
        audioManager.mode = previousMode
    }

    private fun releaseResource(
        name: String,
        release: () -> Unit,
    ) {
        runCatching(release).onFailure {
            if (it is InterruptedException) Thread.currentThread().interrupt()
            Log.w(TAG, "Failed to release $name", it)
        }
    }

    private fun releaseVideoCapture() {
        releaseResource("camera capture") { videoCapturer?.stopCapture() }
        releaseResource("camera capturer") { videoCapturer?.dispose() }
        videoCapturer = null
        releaseResource("video track") { localVideoTrack?.dispose() }
        localVideoTrack = null
        releaseResource("video source") { videoSource?.dispose() }
        videoSource = null
        releaseResource("surface texture helper") { surfaceTextureHelper?.dispose() }
        surfaceTextureHelper = null
    }

    /** Idempotent, terminal shutdown. Call off WebRTC observer threads, after peers/renderers stop. */
    @Synchronized
    fun dispose() {
        if (disposed) return
        disposed = true
        releaseVideoCapture()
        releaseResource("audio track") { localAudioTrack?.dispose() }
        localAudioTrack = null
        releaseResource("audio source") { audioSource?.dispose() }
        audioSource = null
        captureStarted = false
        releaseResource("peer connection factory") { factory.dispose() }
        releaseResource("EGL") { eglBase.release() }
        releaseResource("audio routing") { restoreAudioMode() }
    }
}
