package com.michaeltchuang.walletsdk.core.liquidAuth.auth.connect

import com.michaeltchuang.walletsdk.core.liquidAuth.domain.model.IceCandidateWireKeys
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.model.parseIceCandidateMessage
import org.json.JSONObject
import org.webrtc.IceCandidate

/**
 * Parses a trickle ICE candidate signaling frame, or `null` if the frame is malformed
 * (missing/blank candidate, or a missing/negative `sdpMLineIndex`) - mirroring the defensive
 * handling on iOS (`SignalClient.swift.handleIceCandidate`) instead of throwing.
 */
fun JSONObject.toIceCandidate(): IceCandidate? {
    val sdpMLineIndex =
        if (has(IceCandidateWireKeys.SDP_M_LINE_INDEX) && !isNull(IceCandidateWireKeys.SDP_M_LINE_INDEX)) {
            optInt(IceCandidateWireKeys.SDP_M_LINE_INDEX, -1)
        } else {
            -1
        }
    val message =
        parseIceCandidateMessage(
            candidate = optStringOrNull(IceCandidateWireKeys.CANDIDATE),
            sdpMid = optStringOrNull(IceCandidateWireKeys.SDP_MID),
            sdpMLineIndex = sdpMLineIndex,
        ) ?: return null
    return IceCandidate(message.sdpMid, message.sdpMLineIndex, message.candidate)
}

private fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
