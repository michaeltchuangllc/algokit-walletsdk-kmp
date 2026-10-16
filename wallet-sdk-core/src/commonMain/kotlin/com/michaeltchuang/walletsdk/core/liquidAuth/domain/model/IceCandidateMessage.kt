/*
 * Copyright 2025 Algorand Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.michaeltchuang.walletsdk.core.liquidAuth.domain.model

/**
 * Wire-format keys for a trickle ICE candidate message exchanged over the Liquid Auth signaling
 * socket (`offer-candidate` / `answer-candidate` / `candidate` events). Shared so Android
 * (`IceCandidateExt.kt`) and iOS (`SignalClient.swift`) can never drift on the wire shape.
 */
object IceCandidateWireKeys {
    const val CANDIDATE = "candidate"
    const val SDP_MID = "sdpMid"
    const val SDP_M_LINE_INDEX = "sdpMLineIndex"
}

/** A validated trickle ICE candidate, ready to hand to the native WebRTC peer connection. */
data class IceCandidateMessage(
    val candidate: String,
    val sdpMid: String?,
    val sdpMLineIndex: Int,
)

/**
 * Validates and extracts a trickle ICE candidate from generic wire fields already decoded from
 * JSON by the caller (Android's `org.json.JSONObject`, iOS's `[String: Any]` from Socket.IO).
 *
 * A blank candidate string or a missing/negative `sdpMLineIndex` means "drop this message" -
 * not "crash" - matching the defensive behavior both platforms should have for malformed or
 * late-arriving signaling messages. Pass `-1` for [sdpMLineIndex] when the field is missing or
 * not an integer, so platforms don't need to bridge a nullable/optional int across the boundary.
 */
fun parseIceCandidateMessage(
    candidate: String?,
    sdpMid: String?,
    sdpMLineIndex: Int,
): IceCandidateMessage? {
    if (candidate.isNullOrBlank()) return null
    if (sdpMLineIndex < 0) return null
    return IceCandidateMessage(candidate = candidate, sdpMid = sdpMid, sdpMLineIndex = sdpMLineIndex)
}
