package com.michaeltchuang.walletsdk.core.liquidAuth.auth.connect

import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection

internal fun addBroadcastTrack(
    peerConnection: PeerConnection,
    track: MediaStreamTrack,
    streamIds: List<String>,
    initiallyActive: Boolean,
) {
    try {
        val sender = peerConnection.addTrack(track, streamIds)
        if (!initiallyActive) {
            // Use the returned wrapper immediately: getSenders() would dispose it.
            val parameters = sender.parameters
            check(parameters.encodings.isNotEmpty()) { "Host media sender has no RTP encodings to gate" }
            parameters.encodings.forEach { it.active = false }
            check(sender.setParameters(parameters)) { "Unable to gate host media sender" }
        }
    } catch (error: Throwable) {
        if (!initiallyActive) {
            // Only this peer is closed; never disable or dispose the shared capture track.
            try {
                peerConnection.close()
            } catch (closeError: Throwable) {
                if (closeError !== error) error.addSuppressed(closeError)
            }
        }
        throw error
    }
}
