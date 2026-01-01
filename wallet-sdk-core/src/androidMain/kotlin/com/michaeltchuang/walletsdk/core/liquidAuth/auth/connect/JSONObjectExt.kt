package com.michaeltchuang.walletsdk.core.liquidAuth.auth.connect

import org.json.JSONObject
import org.webrtc.IceCandidate

fun JSONObject.toIceCandidate(): IceCandidate = IceCandidate(getString("sdpMid"), getInt("sdpMLineIndex"), getString("candidate"))
