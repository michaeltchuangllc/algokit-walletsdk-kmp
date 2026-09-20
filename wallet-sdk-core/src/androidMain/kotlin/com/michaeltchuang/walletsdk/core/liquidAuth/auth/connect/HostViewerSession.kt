package com.michaeltchuang.walletsdk.core.liquidAuth.auth.connect

import kotlinx.coroutines.Job
import org.webrtc.DataChannel

/** One independent legacy signaling link and WebRTC connection in a host broadcast. */
class HostViewerSession(
    val requestId: String,
    val client: SignalClient,
) {
    val peer: PeerApi? get() = client.peerClient
    var dataChannel: DataChannel? = null
        internal set
    internal var job: Job? = null
    internal var invitationExpiry: Job? = null
    internal var connected = false
    internal var onDisconnected: ((String) -> Unit)? = null

    fun createDataChannel(label: String): DataChannel? =
        peer?.getAdditionalDataChannel(label) ?: peer?.createAdditionalDataChannel(label)

    fun send(message: String) {
        peer?.send(message)
    }

    internal fun close() {
        onDisconnected = null
        client.onFailure = null
        job?.cancel()
        job = null
        invitationExpiry?.cancel()
        invitationExpiry = null
        dataChannel?.unregisterObserver()
        dataChannel = null
        client.disconnect()
    }
}
