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

package com.michaeltchuang.walletsdk.core.liquidAuth.auth.connect

import com.michaeltchuang.walletsdk.core.liquidAuth.domain.model.IceCandidatePairStat
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.model.IceTransportStat
import org.webrtc.RTCStatsReport

/**
 * Adapts Android's native `org.webrtc.RTCStatsReport` into the shared, engine-agnostic model
 * consumed by `classifyIceConnectionType`, so the classification logic itself lives in exactly
 * one place (`IceConnectionTypeClassifier.kt`) instead of being re-implemented per call site.
 */
fun RTCStatsReport.toIceTransportStats(): List<IceTransportStat> =
    statsMap.values
        .filter { it.type == "transport" }
        .map { IceTransportStat(selectedCandidatePairId = it.members["selectedCandidatePairId"]?.toString()) }

/** @see toIceTransportStats */
fun RTCStatsReport.toIceCandidatePairStats(): List<IceCandidatePairStat> =
    statsMap.values
        .filter { it.type == "candidate-pair" }
        .map { pair ->
            IceCandidatePairStat(
                id = pair.id,
                state = pair.members["state"]?.toString(),
                isSelectedOrNominated =
                    pair.members["selected"]?.toString() == "true" ||
                        pair.members["nominated"]?.toString() == "true",
                localCandidateType =
                    statsMap[pair.members["localCandidateId"]?.toString()]
                        ?.members
                        ?.get("candidateType")
                        ?.toString(),
                remoteCandidateType =
                    statsMap[pair.members["remoteCandidateId"]?.toString()]
                        ?.members
                        ?.get("candidateType")
                        ?.toString(),
            )
        }
