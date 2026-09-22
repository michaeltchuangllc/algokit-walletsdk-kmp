package com.michaeltchuang.walletsdk.core.liquidAuth.auth.connect

import android.content.Context
import android.util.Log
import org.webrtc.DataChannel
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Owns a single connection and its data channels. With [sharedMedia], media resources are borrowed
 * from the service and are never released by [destroy]. Without it, media is privately owned.
 * Serialize connection lifecycle calls on the service's thread, outside WebRTC observer callbacks.
 */
class PeerApi
    @JvmOverloads
    constructor(
        context: Context,
        sharedMedia: SharedBroadcastMedia? = null,
    ) {
        companion object {
            const val TAG = "connect.PeerApi"
        }

        private val ownsMedia = sharedMedia == null
        private val media = sharedMedia ?: SharedBroadcastMedia(context)

        @Volatile
        private var destroyed = false

        @Volatile
        private var connectionGeneration = 0

        // Data Channel to send and receive messages
        @Volatile
        private var dataChannel: DataChannel? = null
        private val dataChannelLock = Any()
        private val additionalDataChannels: MutableMap<String, DataChannel> = mutableMapOf()
        private val ownedDataChannels: MutableSet<DataChannel> = mutableSetOf()

        val eglBaseContext: EglBase.Context get() = media.eglBaseContext

        /** Local camera track, used by the creator/host for preview + sending. */
        val localVideoTrack: VideoTrack? get() = if (destroyed) null else media.localVideoTrack

        /** Remote camera track received from the peer, used by the viewer for rendering. */
        var remoteVideoTrack: VideoTrack? = null
            private set

        /** Invoked on the signaling thread whenever a remote video track arrives. */
        var onRemoteVideoTrack: ((VideoTrack?) -> Unit)? = null

        /**
         * Per-peer notification on WebRTC's signaling thread; never tears down media automatically.
         * Post any destroy/reconnect work to the service thread, rather than disposing in this callback.
         */
        var onIceConnectionStateChange: ((PeerConnection.IceConnectionState) -> Unit)? = null

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
            check(!destroyed) { "PeerApi is destroyed" }
            releasePeerConnection()
            val generation = connectionGeneration

            val rtcConfig =
                PeerConnection
                    .RTCConfiguration(iceServers ?: emptyList())
                    .apply {
                        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                    }

            peerConnection =
                media.factory.createPeerConnection(
                    rtcConfig,
                    object : PeerConnection.Observer {
                        override fun onIceCandidate(p0: IceCandidate?) {
                            if (destroyed || generation != connectionGeneration) return
                            p0?.let {
                                onIceCandidate(it)
                            }
                        }

                        override fun onDataChannel(p0: DataChannel?) {
                            Log.d(TAG, "onDataChannel($p0)")
                            val incomingChannel = p0 ?: return
                            val accepted =
                                synchronized(dataChannelLock) {
                                    if (destroyed || generation != connectionGeneration) {
                                        false
                                    } else {
                                        ownedDataChannels.add(incomingChannel)
                                        val label = incomingChannel.label()
                                        if (label == "liquid" || dataChannel == null) {
                                            dataChannel = incomingChannel
                                        } else {
                                            additionalDataChannels[label] = incomingChannel
                                        }
                                        true
                                    }
                                }
                            if (!accepted) {
                                disposeDataChannel(incomingChannel)
                                return
                            }
                            onDataChannel(incomingChannel)
                        }

                        override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {
                            if (destroyed || generation != connectionGeneration) return
                            Log.d(TAG, "onIceConnectionChange($p0)")
                            if (p0 === PeerConnection.IceConnectionState.FAILED) {
                                Log.e(TAG, "ICE Connection Failed")
                            }
                            p0?.let { onIceConnectionStateChange?.invoke(it) }
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
                            if (destroyed || generation != connectionGeneration) return
                            Log.d(TAG, "onAddTrack($p0, $p1)")
                            (p0?.track() as? VideoTrack)?.let { handleRemoteVideoTrack(it) }
                        }

                        override fun onTrack(transceiver: RtpTransceiver?) {
                            if (destroyed || generation != connectionGeneration) return
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
            disposeDataChannels()
            val channel = peerConnection?.createDataChannel(label, DataChannel.Init())
            synchronized(dataChannelLock) {
                dataChannel = channel
                channel?.let { ownedDataChannels.add(it) }
            }
            return channel
        }

        fun createAdditionalDataChannel(label: String): DataChannel? {
            if (peerConnection === null) {
                throw Exception("peerConnection is null")
            }
            val channel = peerConnection?.createDataChannel(label, DataChannel.Init())
            if (channel != null) {
                synchronized(dataChannelLock) {
                    ownedDataChannels.add(channel)
                    additionalDataChannels[label] = channel
                }
            }
            return channel
        }

        fun getAdditionalDataChannel(label: String): DataChannel? = synchronized(dataChannelLock) { additionalDataChannels[label] }

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

        /** Starts (or reuses) capture and attaches it to this connection before SDP negotiation. */
        fun startLocalCapture(
            width: Int = SharedBroadcastMedia.DEFAULT_CAPTURE_WIDTH,
            height: Int = SharedBroadcastMedia.DEFAULT_CAPTURE_HEIGHT,
            fps: Int = SharedBroadcastMedia.DEFAULT_CAPTURE_FPS,
        ): VideoTrack? {
            check(!destroyed) { "PeerApi is destroyed" }
            val pc =
                peerConnection ?: run {
                    Log.w(TAG, "startLocalCapture skipped: peerConnection is null")
                    return null
                }
            val track = media.startCapture(width, height, fps)
            media.attachTracks(pc)
            return track
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
            media.configureAudioForStreaming()
            Log.d(TAG, "Added recv-only video + audio transceivers")
        }

        /** Toggle front/back cameras for all peers using this media owner (creator side). */
        fun switchCamera() {
            if (!destroyed) media.switchCamera()
        }

        fun setAudioEnabled(enabled: Boolean) {
            if (!destroyed) media.setAudioEnabled(enabled)
        }

        fun setVideoEnabled(enabled: Boolean) {
            if (!destroyed) media.setVideoEnabled(enabled)
        }

        private fun disposeDataChannel(channel: DataChannel) {
            runCatching { channel.unregisterObserver() }
                .onFailure { Log.w(TAG, "Failed to unregister data channel observer", it) }
            runCatching { channel.close() }
                .onFailure { Log.w(TAG, "Failed to close data channel", it) }
            runCatching { channel.dispose() }
                .onFailure { Log.w(TAG, "Failed to dispose data channel", it) }
        }

        private fun disposeDataChannels() {
            val channels =
                synchronized(dataChannelLock) {
                    ownedDataChannels.toList().also {
                        ownedDataChannels.clear()
                        dataChannel = null
                        additionalDataChannels.clear()
                    }
                }
            channels.forEach { disposeDataChannel(it) }
        }

        private fun releasePeerConnection() {
            // Invalidate callbacks from the previous connection before native close/dispose.
            connectionGeneration++
            val connection = peerConnection
            peerConnection = null
            remoteVideoTrack = null
            disposeDataChannels()
            // dispose() closes the connection and releases its senders/receivers/transceivers.
            // Do not separately dispose remote tracks or the media owner's local track wrappers.
            runCatching { connection?.dispose() }
                .onFailure { Log.w(TAG, "Failed to dispose peer connection", it) }
        }

        /** Terminal and idempotent. Must not be called synchronously from a WebRTC observer. */
        fun destroy() {
            if (destroyed) return
            destroyed = true
            onRemoteVideoTrack = null
            onIceConnectionStateChange = null
            releasePeerConnection()
            if (ownsMedia) media.dispose()
        }
    }
