package com.michaeltchuang.walletsdk.core.liquidAuth.auth.connect

import com.michaeltchuang.walletsdk.core.liquidAuth.domain.model.IceCandidateWireKeys
import org.json.JSONObject
import org.webrtc.IceCandidate

/**
 * Encodes a trickle ICE candidate using the shared wire keys ([IceCandidateWireKeys]) so this
 * can never drift from the shape iOS (`SignalClient.swift`) sends/expects.
 */
fun IceCandidate.toJSON(): JSONObject =
    JSONObject().apply {
        put(IceCandidateWireKeys.CANDIDATE, sdp)
        put(IceCandidateWireKeys.SDP_MID, sdpMid)
        put(IceCandidateWireKeys.SDP_M_LINE_INDEX, sdpMLineIndex)
    }
