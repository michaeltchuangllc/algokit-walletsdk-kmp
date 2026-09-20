package com.michaeltchuang.walletsdk.core.liquidAuth.auth.connect

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.socket.client.Ack
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.emitter.Emitter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import qrcode.QRCode
import qrcode.color.Colors
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * Signal Client
 *
 * Has two modes:
 * The type names describe the REMOTE peer: "offer" is the host, which answers;
 * "answer" is the viewer, which creates an offer.
 */
class SignalClient
    @Inject
    @JvmOverloads
    constructor(
        /**
         * Origin of the Service
         */
        override val url: String,
        /**
         * Android Context
         */
        override val context: Context,
        /**
         * HTTP Client
         */
        override val client: OkHttpClient,
        private val sharedMedia: SharedBroadcastMedia? = null,
    ) : SignalInterface {
        companion object {
            const val TAG = "connect.SignalClient"
            private const val EXCHANGE_TIMEOUT_MS = 30_000L

            fun generateRequestId(): String = SignalInterface.Companion.generateRequestId()
        }

        var type: String? = null
        override var socket: Socket? = null
        var peerClient: PeerApi? = null

        /**
         * Terminal session failure, delivered once on Main after marking the session
         * closed but BEFORE disposing its peer/data channels. Remove registry entries
         * and unregister channel observers synchronously here; do not defer native reads.
         * Calling disconnect() here is safe. Explicit disconnect/cancellation and
         * signaling-only disruption after a successful exchange do not notify.
         */
        var onFailure: ((Throwable) -> Unit)? = null

        private val mainHandler = Handler(Looper.getMainLooper())

        @Volatile
        private var session: Session? = null

        /** All session state and native peer operations are confined to Main. */
        private inner class Session(
            val socket: Socket,
        ) {
            val failure = CompletableDeferred<Nothing>()
            val connected = CompletableDeferred<Unit>()
            val primaryChannel = CompletableDeferred<DataChannel>()
            val candidates = mutableListOf<IceCandidate>()
            val listeners = mutableListOf<Pair<String, Emitter.Listener>>()
            var peer: PeerApi? = null
            var exchangeJob: Job? = null
            var remoteDescriptionSet = false
            var established = false

            @Volatile
            var closed = false

            // Socket.IO and WebRTC callbacks may outlive both cancellation and their socket.
            // Never launch them in a detached scope or address the next generation's peer.
            fun dispatch(block: () -> Unit) {
                if (closed) return
                mainHandler.post {
                    if (!closed && session === this) {
                        try {
                            block()
                        } catch (error: Exception) {
                            close(error)
                        }
                    }
                }
            }

            fun listen(
                event: String,
                block: (Array<out Any>) -> Unit,
            ): Emitter.Listener {
                val listener = Emitter.Listener { args -> dispatch { block(args) } }
                listeners.add(event to listener)
                socket.on(event, listener)
                return listener
            }

            fun unlisten(
                event: String,
                listener: Emitter.Listener,
            ) {
                socket.off(event, listener)
                listeners.remove(event to listener)
            }

            fun emit(
                event: String,
                value: Any,
            ) {
                check(!closed) { "Signaling session is closed" }
                // Do not accumulate trickle ICE in a dead socket's outbound buffer.
                if (established && !socket.connected()) return
                socket.emit(event, value)
            }

            fun signalingFailure(cause: Throwable) {
                if (established) {
                    // Signaling is no longer the lifetime owner of the live media.
                    // Keep generation-guarded candidate listeners; ICE/service teardown
                    // or explicit disconnect still owns the established connection.
                    Log.w(TAG, "Signaling unavailable; preserving established peer", cause)
                } else {
                    close(cause)
                }
            }

            fun close(cause: Throwable) {
                if (closed) return
                closed = true
                // Mark closed first so recursive service removal/disconnect is a no-op.
                // Notify before waking waiters or touching any native channel: the
                // service must detach its observers while their handles are still valid.
                if (cause !is CancellationException) {
                    runCatching { onFailure?.invoke(cause) }
                        .onFailure { Log.w(TAG, "Session failure callback failed", it) }
                }
                // Wake every pending public wait before removing listeners/disconnecting.
                failure.completeExceptionally(cause)
                exchangeJob?.cancel()
                exchangeJob = null
                listeners.forEach { (event, listener) -> socket.off(event, listener) }
                listeners.clear()
                candidates.clear()
                runCatching { socket.disconnect() }
                    .onFailure { Log.w(TAG, "Socket teardown failed", it) }
                // PeerApi owns this peer only; sharedMedia belongs to the broadcast owner.
                runCatching { peer?.destroy() }
                    .onFailure { Log.w(TAG, "Peer teardown failed", it) }
                if (session === this) {
                    session = null
                    this@SignalClient.socket = null
                    peerClient = null
                }
            }
        }

        /**
         * Generate a random Request ID
         * @TODO: Replace with UUID
         */
        override fun generateRequestId(): String = SignalClient.generateRequestId()

        /**
         * Generate a QR Code
         */
        override fun qrCode(
            requestId: String,
            logo: Bitmap?,
            logoSize: Int?,
            color: String?,
            backgroundColor: String?,
        ): Bitmap {
            val size = logoSize ?: 200
            val scaledLogo = logo?.let { Bitmap.createScaledBitmap(it, size, size, false) }
            val stream = ByteArrayOutputStream()
            scaledLogo?.compress(Bitmap.CompressFormat.PNG, 100, stream)
            val data = "liquid://${url.replace("https://", "")}/?requestId=$requestId"
            val image =
                QRCode
                    .ofSquares()
                    .withColor(Colors.css(color ?: "#9966FF"))
                    .withBackgroundColor(Colors.css(backgroundColor ?: "#15121B"))
                    .withLogo(stream.toByteArray(), size, size)
                    .build(data)
                    .render()
                    .nativeImage()
            if (image !is Bitmap) {
                throw Exception("Invalid Type")
            }
            return image
        }

        /**
         * Top Level Peer Connection
         *
         * The type parameter is used to specify the type of remote peer
         */
        override suspend fun peer(
            requestId: String,
            type: String,
            iceServers: List<PeerConnection.IceServer>?,
            enableMedia: Boolean,
        ): DataChannel? {
            var ownedSession: Session? = null
            try {
                return withContext(Dispatchers.Main.immediate) {
                    coroutineScope {
                        require(type == "offer" || type == "answer") { "Unknown remote peer type: $type" }
                        disconnect()
                        this@SignalClient.type = type
                        val current = Session(createSocket())
                        session = current
                        ownedSession = current
                        val description = CompletableDeferred<SessionDescription>()
                        listenForDescription(current, type, description)
                        current.listen(Socket.EVENT_CONNECT) { current.connected.complete(Unit) }
                        current.listen(Socket.EVENT_CONNECT_ERROR) { args ->
                            current.signalingFailure(IllegalStateException("Signaling connection failed: ${args.firstOrNull()}"))
                        }
                        current.listen(Socket.EVENT_DISCONNECT) { args ->
                            current.signalingFailure(IllegalStateException("Signaling disconnected: ${args.firstOrNull()}"))
                        }
                        current.listen("$type-candidate") { args ->
                            val json = args.firstOrNull() as? JSONObject ?: error("Invalid ICE candidate")
                            val candidate = json.toIceCandidate()
                            if (current.remoteDescriptionSet) {
                                current.peer!!.addIceCandidate(candidate)
                            } else {
                                current.candidates.add(candidate)
                            }
                        }

                        val peer = PeerApi(context, sharedMedia)
                        current.peer = peer
                        peerClient = peer
                        peer.onIceConnectionStateChange = { state ->
                            current.dispatch {
                                if (
                                    state == PeerConnection.IceConnectionState.FAILED ||
                                    state == PeerConnection.IceConnectionState.CLOSED
                                ) {
                                    current.close(IllegalStateException("ICE connection $state"))
                                }
                            }
                        }
                        val localType = if (type == "offer") "answer" else "offer"
                        peer.createPeerConnection(
                            { candidate ->
                                current.dispatch { current.emit("$localType-candidate", candidate.toJSON()) }
                            },
                            { channel ->
                                current.dispatch {
                                    // Additional channels must never resolve/replace the primary result.
                                    if (channel.label() == "liquid") current.primaryChannel.complete(channel)
                                }
                            },
                            iceServers,
                        )
                        val pc = checkNotNull(peer.peerConnection) { "Failed to create peer connection" }
                        // Every inbound listener is installed before connect, link or any SDP emit.
                        current.socket.connect()
                        val exchange =
                            async {
                                current.connected.await()
                                val channel =
                                    if (type == "offer") {
                                        coroutineScope {
                                            val linked = async { awaitLink(current, requestId) }
                                            // A host can wait indefinitely for its first viewer. In particular,
                                            // a slow/missing link ACK cannot hide an already-received offer.
                                            val offer = description.await()
                                            withTimeout(EXCHANGE_TIMEOUT_MS) {
                                                linked.await()
                                                if (enableMedia) peer.startLocalCapture()
                                                setRemoteDescription(current, pc, offer)
                                                val answer = createDescription(pc, offer = false)
                                                setLocalDescription(pc, answer)
                                                current.emit("answer-description", answer.description)
                                                current.primaryChannel.await()
                                            }
                                        }
                                    } else {
                                        withTimeout(EXCHANGE_TIMEOUT_MS) {
                                            if (enableMedia) peer.addReceiveOnlyMediaTransceivers()
                                            val channel = checkNotNull(peer.createDataChannel("liquid")) {
                                                "Failed to create primary data channel"
                                            }
                                            val offer = createDescription(pc, offer = true)
                                            setLocalDescription(pc, offer)
                                            current.emit("offer-description", offer.description)
                                            setRemoteDescription(current, pc, description.await())
                                            channel
                                        }
                                    }
                                // Set on Main before completing the deferred, not after
                                // returning to the caller: queued socket errors must not
                                // tear down a successfully exchanged peer in that gap.
                                current.established = true
                                channel
                            }
                        current.exchangeJob = exchange
                        try {
                            select {
                                current.failure.onAwait { it }
                                exchange.onAwait { it }
                            }
                        } finally {
                            exchange.cancel()
                            current.exchangeJob = null
                        }
                        // On success, keep trickle ICE/error listeners alive until disconnect().
                        // They dispatch onto Main without retaining a completed coroutine scope.
                    }
                }
            } catch (error: Throwable) {
                // Also covers prompt cancellation while withContext hands its result
                // back to a caller on a different dispatcher.
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    ownedSession?.close(error)
                }
                throw error
            }
        }

        fun handleDataChannel(
            dataChannel: DataChannel,
            onMessage: (String) -> Unit,
            onStateChange: ((String?) -> Unit)? = null,
            onBufferedAmountChange: ((Long) -> Unit)? = null,
        ) {
            dataChannel.registerObserver(
                peerClient!!.createDataChannelObserver(
                    onMessage,
                    onStateChange,
                    onBufferedAmountChange,
                ),
            )
        }

        /**
         * Wait for a Session Description
         */
        override suspend fun signal(type: String): SessionDescription =
            withContext(Dispatchers.Main.immediate) {
                require(type == "offer" || type == "answer") { "Unknown remote peer type: $type" }
                val current = checkNotNull(session) { "No signaling session" }
                val description = CompletableDeferred<SessionDescription>()
                val listener = listenForDescription(current, type, description)
                try {
                    select {
                        current.failure.onAwait { it }
                        description.onAwait { it }
                    }
                } finally {
                    description.cancel()
                    current.unlisten("$type-description", listener)
                }
            }

        override suspend fun link(requestId: String): LinkMessage =
            withContext(Dispatchers.Main.immediate) {
                awaitLink(checkNotNull(session) { "No signaling session" }, requestId)
            }

        private fun listenForDescription(
            current: Session,
            type: String,
            result: CompletableDeferred<SessionDescription>,
        ): Emitter.Listener {
            val event = "$type-description"
            lateinit var listener: Emitter.Listener
            listener =
                current.listen(event) { args ->
                    if (!result.isCompleted) {
                        val description = args.firstOrNull() as? String ?: error("Invalid remote SDP")
                        val sdpType = if (type == "offer") SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER
                        result.complete(SessionDescription(sdpType, description))
                        current.unlisten(event, listener)
                    }
                }
            return listener
        }

        private suspend fun awaitLink(
            current: Session,
            requestId: String,
        ): LinkMessage {
            val result = CompletableDeferred<LinkMessage>()
            try {
                check(!current.closed) { "Signaling session is closed" }
                current.socket.emit(
                    "link",
                    JSONObject().put("requestId", requestId),
                    Ack { args ->
                        current.dispatch {
                            // ACKs cannot be unregistered; cancellation invalidates their result.
                            if (!result.isCompleted) {
                                result.complete(
                                    LinkMessage.fromJson(
                                        (args.firstOrNull() as? JSONObject ?: error("Invalid link acknowledgement")).toString(),
                                    ),
                                )
                            }
                        }
                    },
                )
                return select {
                    current.failure.onAwait { it }
                    result.onAwait { it }
                }
            } finally {
                result.cancel()
            }
        }

        private suspend fun setRemoteDescription(
            current: Session,
            pc: PeerConnection,
            description: SessionDescription,
        ) {
            awaitSdp("set remote description", description) { pc.setRemoteDescription(it, description) }
            current.remoteDescriptionSet = true
            current.candidates.forEach { current.peer!!.addIceCandidate(it) }
            current.candidates.clear()
        }

        private suspend fun setLocalDescription(
            pc: PeerConnection,
            description: SessionDescription,
        ) {
            awaitSdp("set local description", description) { pc.setLocalDescription(it, description) }
        }

        private suspend fun createDescription(
            pc: PeerConnection,
            offer: Boolean,
        ): SessionDescription =
            awaitSdp(if (offer) "create offer" else "create answer") { observer ->
                if (offer) pc.createOffer(observer, MediaConstraints()) else pc.createAnswer(observer, MediaConstraints())
            }

        /**
         * Do not use PeerApi's legacy SDP helpers: set failures must complete the
         * suspension, and a remote set must not read back the local description.
         */
        private suspend fun awaitSdp(
            operation: String,
            setting: SessionDescription? = null,
            start: (SdpObserver) -> Unit,
        ): SessionDescription =
            suspendCancellableCoroutine { continuation ->
                val finished = AtomicBoolean(false)
                continuation.invokeOnCancellation { finished.set(true) }
                fun finish(result: Result<SessionDescription>) {
                    if (finished.compareAndSet(false, true)) continuation.resumeWith(result)
                }
                fun fail(message: String?) {
                    finish(Result.failure(IllegalStateException("$operation failed: $message")))
                }
                val observer =
                    object : SdpObserver {
                        override fun onCreateSuccess(description: SessionDescription?) {
                            if (description == null) fail("No SDP returned") else finish(Result.success(description))
                        }

                        override fun onSetSuccess() {
                            if (setting == null) fail("Unexpected set callback") else finish(Result.success(setting))
                        }

                        override fun onCreateFailure(message: String?) = fail(message)

                        override fun onSetFailure(message: String?) = fail(message)
                    }
                if (continuation.isActive) {
                    try {
                        start(observer)
                    } catch (error: Exception) {
                        finish(Result.failure(error))
                    }
                }
            }

        private fun createSocket(): Socket {
            // The wire protocol has no generation ID. A new Manager/transport for
            // every session prevents same-origin hosts/viewers sharing signaling.
            val options =
                IO.Options
                    .builder()
                    .setForceNew(true)
                    .setMultiplex(false)
                    .setReconnection(false)
                    .build()
            options.callFactory = client
            options.webSocketFactory = client
            return IO.socket(url, options).also { socket = it }
        }

        fun disconnect() {
            val current = session ?: return
            if (Looper.myLooper() == Looper.getMainLooper()) {
                current.close(CancellationException("Signaling disconnected"))
            } else {
                mainHandler.post { current.close(CancellationException("Signaling disconnected")) }
            }
        }
    }
