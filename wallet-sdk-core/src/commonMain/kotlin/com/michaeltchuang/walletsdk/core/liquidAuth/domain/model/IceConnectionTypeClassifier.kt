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
 * Minimal, engine-agnostic snapshot of a single WebRTC "transport" stats entry.
 *
 * Android (`org.webrtc.RTCStatsReport`) and iOS (`WebRTC.RTCStatisticsReport`) both expose the
 * standard `RTCStatsReport` shape from the WebRTC spec, so each platform only needs to adapt its
 * native stats into this shared, primitive-only model before calling [classifyIceConnectionType].
 */
data class IceTransportStat(
    val selectedCandidatePairId: String?,
)

/** Minimal, engine-agnostic snapshot of a single WebRTC "candidate-pair" stats entry. */
data class IceCandidatePairStat(
    val id: String,
    val state: String?,
    /** True if the native stats report marks this pair as `selected` and/or `nominated`. */
    val isSelectedOrNominated: Boolean,
    val localCandidateType: String?,
    val remoteCandidateType: String?,
)

/** Result of [classifyIceConnectionType]. */
enum class IceConnectionClass {
    LOCAL,
    STUN,
    RELAY,
    UNKNOWN,
}

/**
 * Classifies the currently active ICE connection type from a WebRTC stats snapshot.
 *
 * This is shared by both platforms so connection-quality decisions - including x402-style
 * billing tiers (`IceConnectionType.suggestedPricingTier` in `wallet-sdk-ui`) - can never
 * disagree between Android and iOS for the same underlying connection.
 *
 * Algorithm:
 * 1. Prefer the pair(s) referenced by a `transport` entry's `selectedCandidatePairId` - this is
 *    the spec-correct way to find the pair actually carrying traffic right now.
 * 2. If no transport selection is available (older/incomplete stats), fall back to `succeeded`
 *    pairs explicitly marked selected/nominated - but only when exactly one such pair exists.
 *    Multiple nominated pairs without a selected transport are ambiguous.
 * 3. Classify by local + remote candidate type: any `relay` wins (weakest connection, highest
 *    cost), `srflx`/`prflx` means NAT traversal via STUN, and both sides `host` means a direct
 *    local/LAN connection.
 *
 * Note: this only classifies *quality*, it cannot detect a hard failure. Callers should check
 * their native peer connection/ICE connection state for `failed` and short-circuit before
 * calling this (see `SignalService.detectConnectionType` on Android).
 */
fun classifyIceConnectionType(
    transports: List<IceTransportStat>,
    candidatePairs: List<IceCandidatePairStat>,
): IceConnectionClass {
    val pairsById = candidatePairs.associateBy { it.id }

    var selectedPairs = transports.mapNotNull { it.selectedCandidatePairId?.let(pairsById::get) }
    if (selectedPairs.isEmpty()) {
        val nominated = candidatePairs.filter { it.state == "succeeded" && it.isSelectedOrNominated }
        // Several nominated pairs without a selected transport are ambiguous.
        if (nominated.size != 1) return IceConnectionClass.UNKNOWN
        selectedPairs = nominated
    }

    val classifications = selectedPairs.map(::classifyPair)
    return when {
        IceConnectionClass.RELAY in classifications -> IceConnectionClass.RELAY
        classifications.isEmpty() || IceConnectionClass.UNKNOWN in classifications -> IceConnectionClass.UNKNOWN
        IceConnectionClass.STUN in classifications -> IceConnectionClass.STUN
        else -> IceConnectionClass.LOCAL
    }
}

private fun classifyPair(pair: IceCandidatePairStat): IceConnectionClass {
    val types = listOfNotNull(pair.localCandidateType, pair.remoteCandidateType)
    return when {
        "relay" in types -> IceConnectionClass.RELAY
        types.size != 2 -> IceConnectionClass.UNKNOWN
        types.any { it == "srflx" || it == "prflx" } -> IceConnectionClass.STUN
        types.all { it == "host" } -> IceConnectionClass.LOCAL
        else -> IceConnectionClass.UNKNOWN
    }
}
