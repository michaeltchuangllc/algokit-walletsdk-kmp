package com.michaeltchuang.walletsdk.core.liquidAuth.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class IceConnectionTypeClassifierTest {
    @Test
    fun `EXPECT UNKNOWN WHEN no stats are provided`() {
        assertEquals(IceConnectionClass.UNKNOWN, classifyIceConnectionType(emptyList(), emptyList()))
    }

    @Test
    fun `EXPECT LOCAL WHEN transport-selected pair is host to host`() {
        val pairs =
            listOf(
                pair("pair-1", localType = "host", remoteType = "host"),
            )
        val transports = listOf(IceTransportStat(selectedCandidatePairId = "pair-1"))

        assertEquals(IceConnectionClass.LOCAL, classifyIceConnectionType(transports, pairs))
    }

    @Test
    fun `EXPECT STUN WHEN transport-selected pair is srflx`() {
        val pairs =
            listOf(
                pair("pair-1", localType = "srflx", remoteType = "host"),
            )
        val transports = listOf(IceTransportStat(selectedCandidatePairId = "pair-1"))

        assertEquals(IceConnectionClass.STUN, classifyIceConnectionType(transports, pairs))
    }

    @Test
    fun `EXPECT RELAY WHEN either side of the selected pair is relay`() {
        val pairs =
            listOf(
                pair("pair-1", localType = "host", remoteType = "relay"),
            )
        val transports = listOf(IceTransportStat(selectedCandidatePairId = "pair-1"))

        assertEquals(IceConnectionClass.RELAY, classifyIceConnectionType(transports, pairs))
    }

    @Test
    fun `EXPECT the single nominated succeeded pair to be classified WHEN there is no transport selection`() {
        val pairs =
            listOf(
                pair("pair-1", localType = "host", remoteType = "host", state = "succeeded", isSelectedOrNominated = true),
                pair("pair-2", localType = "relay", remoteType = "relay", state = "succeeded", isSelectedOrNominated = false),
            )

        assertEquals(IceConnectionClass.LOCAL, classifyIceConnectionType(emptyList(), pairs))
    }

    @Test
    fun `EXPECT UNKNOWN WHEN multiple nominated pairs are ambiguous`() {
        val pairs =
            listOf(
                pair("pair-1", localType = "host", remoteType = "host", state = "succeeded", isSelectedOrNominated = true),
                pair("pair-2", localType = "srflx", remoteType = "host", state = "succeeded", isSelectedOrNominated = true),
            )

        assertEquals(IceConnectionClass.UNKNOWN, classifyIceConnectionType(emptyList(), pairs))
    }

    @Test
    fun `EXPECT UNKNOWN WHEN a candidate type is missing`() {
        val pairs =
            listOf(
                pair("pair-1", localType = "host", remoteType = null),
            )
        val transports = listOf(IceTransportStat(selectedCandidatePairId = "pair-1"))

        assertEquals(IceConnectionClass.UNKNOWN, classifyIceConnectionType(transports, pairs))
    }

    @Test
    fun `EXPECT transport selection to take priority WHEN a stale succeeded pair also exists`() {
        // A stale/losing "succeeded" pair should never override the transport's active pick.
        val pairs =
            listOf(
                pair("pair-1", localType = "host", remoteType = "host"),
                pair("pair-2", localType = "relay", remoteType = "relay", state = "succeeded", isSelectedOrNominated = true),
            )
        val transports = listOf(IceTransportStat(selectedCandidatePairId = "pair-1"))

        assertEquals(IceConnectionClass.LOCAL, classifyIceConnectionType(transports, pairs))
    }

    private fun pair(
        id: String,
        localType: String?,
        remoteType: String?,
        state: String? = null,
        isSelectedOrNominated: Boolean = false,
    ) = IceCandidatePairStat(
        id = id,
        state = state,
        isSelectedOrNominated = isSelectedOrNominated,
        localCandidateType = localType,
        remoteCandidateType = remoteType,
    )
}
