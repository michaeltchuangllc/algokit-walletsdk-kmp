package com.michaeltchuang.walletsdk.core.liquidAuth.auth.connect

import android.content.Context
import android.media.AudioManager
import android.util.Log
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class PeerApi(
    context: Context,
) {
    companion object {
        const val TAG = "connect.PeerApi"

        private const val LOCAL_VIDEO_TRACK_ID = "local_video"
        private const val LOCAL_AUDIO_TRACK_ID = "local_audio"
        private const val LOCAL_MEDIA_STREAM_ID = "liquid_stream"
        private const val DEFAULT_CAPTURE_WIDTH = 1280
        private const val DEFAULT_CAPTURE_HEIGHT = 720
        private const val DEFAULT_CAPTURE_FPS = 30
    }

    // Application context, used for camera capture.
    private val appContext: Context = context.applicationContext

    // Data Channel to send and receive messages
    private var dataChannel: DataChannel? = null
    private val additionalDataChannels: MutableMap<String, DataChannel> = mutableMapOf()

    // Shared EGL context for hardware video encode/decode + rendering.
    private val eglBase: EglBase = EglBase.create()
    val eglBaseContext: EglBase.Context get() = eglBase.eglBaseContext

    // Create the Peer Connection Factory
    private var peerConnectionFactory: PeerConnectionFactory

    // ── Media tracks (native WebRTC video/audio streaming) ──────────────────────
    private var videoCapturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    /** Local camera track, used by the creator/host for preview + sending. */
    var localVideoTrack: VideoTrack? = null
        private set
    private var localAudioTrack: AudioTrack? = null

    /** Remote camera track received from the peer, used by the viewer for rendering. */
    var remoteVideoTrack: VideoTrack? = null
        private set

    /** Invoked on the signaling thread whenever a remote video track arrives. */
    var onRemoteVideoTrack: ((VideoTrack?) -> Unit)? = null

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions
                .builder(context)
                .setEnableInternalTracer(true)
                .createInitializationOptions(),
        )
        peerConnectionFactory =
            PeerConnectionFactory
                .builder()
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
                .setVideoEncoderFactory(
                    DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true),
                ).setOptions(
                    PeerConnectionFactory.Options().apply {
                        disableEncryption = false
                        disableNetworkMonitor = false
                    },
                ).createPeerConnectionFactory()
    }

    // Current Peer Connection
    var peerConnection: PeerConnection? = null

    /**
     * Create a new Peer Connection
     */
    fun createPeerConnection(
        onIceCandidate: (IceCandidate) -> Unit,
        onDataChannel: (DataChannel) -> Unit,
        iceServers: List<PeerConnection.IceServer>? =
            listOf(
                PeerConnection.IceServer
                    .builder("stun:stun.l.google.com:19302")
                    .createIceServer(),
            ),
    ) {
        if (peerConnection !== null) {
            peerConnection?.close()
        }

        val rtcConfig =
            PeerConnection
                .RTCConfiguration(iceServers ?: emptyList())
                .apply {
                    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                }

        peerConnection =
            peerConnectionFactory.createPeerConnection(
                rtcConfig,
                object : PeerConnection.Observer {
                    override fun onIceCandidate(p0: IceCandidate?) {
                        p0?.let {
                            onIceCandidate(it)
                        }
                    }

                    override fun onDataChannel(p0: DataChannel?) {
                        Log.d(TAG, "onDataChannel($p0)")
                        val incomingChannel = p0 ?: return
                        val label = incomingChannel.label()
                        if (label == "liquid" || dataChannel == null) {
                            dataChannel = incomingChannel
                        } else {
                            additionalDataChannels[label] = incomingChannel
                        }
                        onDataChannel(incomingChannel)
                    }

                    override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {
                        Log.d(TAG, "onIceConnectionChange($p0)")
                        if (p0 === PeerConnection.IceConnectionState.FAILED) {
                            Log.e(TAG, "ICE Connection Failed")
                        }
                    }

                    override fun onIceConnectionReceivingChange(p0: Boolean) {
                        Log.d(TAG, "onIceConnectionReceivingChange($p0)")
                    }

                    override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {
                        Log.d(TAG, "onIceGatheringChange($p0)")
                    }

                    override fun onAddStream(p0: MediaStream?) {
                        Log.d(TAG, "onAddStream($p0)")
                    }

                    override fun onSignalingChange(p0: PeerConnection.SignalingState?) {
                        Log.d(TAG, "onSignalingChange($p0)")
                    }

                    override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {
                        Log.d(TAG, "onIceCandidatesRemoved($p0)")
                    }

                    override fun onRemoveStream(p0: MediaStream?) {
                        Log.d(TAG, "onRemoveStream($p0)")
                    }

                    override fun onRenegotiationNeeded() {
                        Log.d(TAG, "onRenegotiationNeeded()")
                    }

                    override fun onAddTrack(
                        p0: RtpReceiver?,
                        p1: Array<out MediaStream>?,
                    ) {
                        Log.d(TAG, "onAddTrack($p0, $p1)")
                        (p0?.track() as? VideoTrack)?.let { handleRemoteVideoTrack(it) }
                    }

                    override fun onTrack(transceiver: RtpTransceiver?) {
                        val track = transceiver?.receiver?.track()
                        Log.d(TAG, "onTrack(${track?.kind()})")
                        (track as? VideoTrack)?.let { handleRemoteVideoTrack(it) }
                    }
                },
            )
    }

    private fun handleRemoteVideoTrack(track: VideoTrack) {
        Log.d(TAG, "Remote video track received: ${track.id()}")
        remoteVideoTrack = track
        track.setEnabled(true)
        onRemoteVideoTrack?.invoke(track)
    }

    suspend fun createPeerConnection(
        onIceCandidate: (IceCandidate) -> Unit,
        iceServers: List<PeerConnection.IceServer>? =
            listOf(
                PeerConnection.IceServer
                    .builder("stun:stun.l.google.com:19302")
                    .createIceServer(),
            ),
    ): DataChannel =
        suspendCoroutine { continuation ->
            createPeerConnection(onIceCandidate, {
                continuation.resume(it)
            }, iceServers)
        }

    /**
     * Add an ICE Candidate
     */
    fun addIceCandidate(candidate: IceCandidate) {
        if (peerConnection === null) {
            throw Exception("peerConnection is null, ensure you are connected")
        }
        peerConnection?.addIceCandidate(candidate)
    }

    fun setLocalDescription(
        description: SessionDescription,
        onSessionDescription: (SessionDescription?) -> Unit,
    ) {
        if (peerConnection === null) {
            throw Exception("peerConnection is null, ensure you are connected")
        }
        peerConnection?.setLocalDescription(createSDPObserver(onSessionDescription), description)
    }

    /**
     * Set the Remote Description
     *
     * Handles Remote Description with a Callback Function
     */
    fun setRemoteDescription(
        description: SessionDescription,
        onSessionDescription: (SessionDescription?) -> Unit,
    ) {
        if (peerConnection === null) {
            throw Exception("peerConnection is null, ensure you are connected")
        }
        peerConnection?.setRemoteDescription(createSDPObserver(onSessionDescription), description)
    }

    /**
     * Set the Remote Description
     *
     * Handles Remote Description using Coroutines
     */
    suspend fun setRemoteDescription(description: SessionDescription): SessionDescription? =
        suspendCoroutine { continuation ->
            setRemoteDescription(description) { sessionDescription ->
                continuation.resume(sessionDescription)
            }
        }

    /**
     * Create an SDP Observer
     *
     * Used for Local and Remote Description handling
     */
    private fun createSDPObserver(onSessionDescription: (SessionDescription?) -> Unit): SdpObserver =
        object : SdpObserver {
            override fun onSetFailure(p0: String?) {
                Log.e(TAG, "onSetFailure: $p0")
            }

            override fun onSetSuccess() {
                Log.d(TAG, "onSetSuccess")
                onSessionDescription(peerConnection?.localDescription)
            }

            override fun onCreateSuccess(p0: SessionDescription?) {
                Log.d(TAG, "onCreateSuccess")
                onSessionDescription(p0)
            }

            override fun onCreateFailure(p0: String?) {
                Log.e(TAG, "onCreateFailure: $p0")
                onSessionDescription(null)
            }
        }

    fun createAnswer(onSessionDescription: (SessionDescription?) -> Unit) {
        Log.d(TAG, "createAnswer")
        if (peerConnection === null) {
            throw Exception("peerConnection is null")
        }
        peerConnection?.createAnswer(createSDPObserver(onSessionDescription), MediaConstraints())
    }

    suspend fun createAnswer(): SessionDescription? =
        suspendCoroutine { continuation ->
            createAnswer { sessionDescription ->
                continuation.resume(sessionDescription)
            }
        }

    /**
     * Create an Offer
     *
     * Handles Offer Creation with a Callback Function
     */
    fun createOffer(onSessionDescription: (SessionDescription?) -> Unit) {
        if (peerConnection === null) {
            throw Exception("peerConnection is null")
        }
        peerConnection?.createOffer(createSDPObserver(onSessionDescription), MediaConstraints())
    }

    /**
     * Create an Offer
     *
     * Handles Offer Creation using Coroutines
     */
    suspend fun createOffer(): SessionDescription? =
        suspendCoroutine { continuation ->
            createOffer { sessionDescription ->
                continuation.resume(sessionDescription)
            }
        }

    fun createDataChannelObserver(
        onMessage: (String) -> Unit,
        onStateChange: ((String?) -> Unit)? = null,
        onBufferedAmountChange: ((Long) -> Unit)? = null,
    ): DataChannel.Observer {
        if (peerConnection === null) {
            throw Exception("peerConnection is null")
        }
        return object : DataChannel.Observer {
            override fun onBufferedAmountChange(p0: Long) {
                Log.d(TAG, "onBufferedAmountChange($p0)")
                onBufferedAmountChange?.invoke(p0)
            }

            override fun onStateChange() {
                Log.d(TAG, "onStateChange")
                onStateChange?.invoke(dataChannel?.state().toString())
            }

            /**
             * Handle DataChannel messages
             *
             * @todo: Implement Web Provider API messages
             */
            override fun onMessage(p0: DataChannel.Buffer?) {
                Log.d(TAG, "onMessage($p0)")
                p0?.data?.let {
                    val bytes = ByteArray(it.remaining())
                    p0.data.get(bytes)
                    val payload = String(bytes)
                    onMessage(payload)
                }
            }
        }
    }

    fun createDataChannel(label: String): DataChannel? {
        if (peerConnection === null) {
            throw Exception("peerConnection is null")
        }
        dataChannel?.close()
        additionalDataChannels.clear()
        val channel = peerConnection?.createDataChannel(label, DataChannel.Init())
        dataChannel = channel
        return channel
    }

    fun createAdditionalDataChannel(label: String): DataChannel? {
        if (peerConnection === null) {
            throw Exception("peerConnection is null")
        }
        val channel = peerConnection?.createDataChannel(label, DataChannel.Init())
        if (channel != null) {
            additionalDataChannels[label] = channel
        }
        return channel
    }

    fun getAdditionalDataChannel(label: String): DataChannel? = additionalDataChannels[label]

    fun send(message: String) {
        val channel = dataChannel
        if (channel == null) {
            Log.w(TAG, "Skipping send: dataChannel is null")
            return
        }
        val state = channel.state()
        if (state !== DataChannel.State.OPEN) {
            Log.w(TAG, "Skipping send: dataChannel is $state")
            return
        }
        val buffer = ByteBuffer.wrap(message.toByteArray())
        val sent = channel.send(DataChannel.Buffer(buffer, false))
        if (!sent) {
            Log.w(TAG, "Skipping send: dataChannel rejected message")
        }
    }

    // ── Native WebRTC media tracks ──────────────────────────────────────────────
    
    fun startLocalCapture(
        width: Int = DEFAULT_CAPTURE_WIDTH,
        height: Int = DEFAULT_CAPTURE_HEIGHT,
        fps: Int = DEFAULT_CAPTURE_FPS,
    ): VideoTrack? {
        val pc =
            peerConnection ?: run {
                Log.w(TAG, "startLocalCapture skipped: peerConnection is null")
                return null
            }
        if (localVideoTrack != null || localAudioTrack != null) {
            Log.d(TAG, "startLocalCapture skipped: capture already started")
            return localVideoTrack
        }
        configureAudioForStreaming()

        // Video capture
        val capturer = createCameraCapturer()
        if (capturer != null) {
            videoCapturer = capturer
            val helper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
            surfaceTextureHelper = helper
            val source = peerConnectionFactory.createVideoSource(capturer.isScreencast)
            videoSource = source
            capturer.initialize(helper, appContext, source.capturerObserver)
            runCatching { capturer.startCapture(width, height, fps) }
                .onFailure { Log.e(TAG, "Failed to start camera capture", it) }
            val track = peerConnectionFactory.createVideoTrack(LOCAL_VIDEO_TRACK_ID, source)
            track.setEnabled(true)
            localVideoTrack = track
            pc.addTrack(track, listOf(LOCAL_MEDIA_STREAM_ID))
            Log.d(TAG, "Local video track added")
        } else {
            Log.w(TAG, "No camera available for local capture")
        }

        // Audio capture
        val aSource = peerConnectionFactory.createAudioSource(MediaConstraints())
        audioSource = aSource
        val aTrack = peerConnectionFactory.createAudioTrack(LOCAL_AUDIO_TRACK_ID, aSource)
        aTrack.setEnabled(true)
        localAudioTrack = aTrack
        pc.addTrack(aTrack, listOf(LOCAL_MEDIA_STREAM_ID))
        Log.d(TAG, "Local audio track added")

        return localVideoTrack
    }
    
    fun addReceiveOnlyMediaTransceivers() {
        val pc =
            peerConnection ?: run {
                Log.w(TAG, "addReceiveOnlyMediaTransceivers skipped: peerConnection is null")
                return
            }
        val init =
            RtpTransceiver.RtpTransceiverInit(
                RtpTransceiver.RtpTransceiverDirection.RECV_ONLY,
            )
        pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, init)
        pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, init)
        configureAudioForStreaming()
        Log.d(TAG, "Added recv-only video + audio transceivers")
    }

    /** Toggle between front/back cameras (creator side). */
    fun switchCamera() {
        videoCapturer?.switchCamera(null)
    }


    fun setAudioEnabled(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
        Log.d(TAG, "Local audio track enabled: $enabled")
    }

    fun setVideoEnabled(enabled: Boolean) {
        localVideoTrack?.setEnabled(enabled)
        Log.d(TAG, "Local video track enabled: $enabled")
    }

    private fun createCameraCapturer(): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(appContext)
        // Prefer the front camera, fall back to the back camera.
        enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }?.let {
            return enumerator.createCapturer(it, null)
        }
        enumerator.deviceNames.firstOrNull { enumerator.isBackFacing(it) }?.let {
            return enumerator.createCapturer(it, null)
        }
        return enumerator.deviceNames.firstOrNull()?.let { enumerator.createCapturer(it, null) }
    }

    private fun stopLocalCapture() {
        runCatching { videoCapturer?.stopCapture() }
        videoCapturer?.dispose()
        videoCapturer = null
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null
        localVideoTrack = null
        localAudioTrack = null
        runCatching { videoSource?.dispose() }
        videoSource = null
        runCatching { audioSource?.dispose() }
        audioSource = null
    }

    // ── Audio routing ─────────────────────────────────────────────────────────────

    // null = audio not yet configured; non-null = saved original mode to restore on destroy().
    private var savedAudioMode: Int? = null
    private var savedSpeakerphoneOn: Boolean = false

    /**
     * Route audio to the loudspeaker for media streaming.
     *
     * WebRTC uses `AudioAttributes.USAGE_VOICE_COMMUNICATION` internally, which Android routes to
     * the earpiece by default. Calling this with `MODE_IN_COMMUNICATION + speakerphoneOn = true`
     * overrides that so viewers and creators both hear through the loudspeaker.
     */
    @Suppress("DEPRECATION")
    private fun configureAudioForStreaming() {
        if (savedAudioMode != null) return // already configured
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        savedAudioMode = am.mode
        savedSpeakerphoneOn = am.isSpeakerphoneOn
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        am.isSpeakerphoneOn = true
        Log.d(TAG, "Audio routed to loudspeaker (was mode=$savedAudioMode, speaker=$savedSpeakerphoneOn)")
    }

    @Suppress("DEPRECATION")
    private fun restoreAudioMode() {
        val previousMode = savedAudioMode ?: return
        savedAudioMode = null
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.isSpeakerphoneOn = savedSpeakerphoneOn
        am.mode = previousMode
        Log.d(TAG, "Audio mode restored to $previousMode, speaker=$savedSpeakerphoneOn")
    }

    fun destroy() {
        restoreAudioMode()
        stopLocalCapture()
        remoteVideoTrack = null
        onRemoteVideoTrack = null
        dataChannel?.close()
//        dataChannel?.dispose()
        additionalDataChannels.values.forEach { it.close() }
        additionalDataChannels.clear()
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
        dataChannel = null
        runCatching { eglBase.release() }
    }
}
