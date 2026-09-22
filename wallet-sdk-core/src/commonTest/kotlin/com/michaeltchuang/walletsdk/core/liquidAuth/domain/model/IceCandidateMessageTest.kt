package com.michaeltchuang.walletsdk.core.liquidAuth.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IceCandidateMessageTest {
    @Test
    fun `EXPECT candidate to parse successfully WHEN all fields are valid`() {
        val message =
            parseIceCandidateMessage(
                candidate = "candidate:1 1 UDP 2122260223 192.168.1.1 5000 typ host",
                sdpMid = "0",
                sdpMLineIndex = 0,
            )

        assertEquals(
            IceCandidateMessage(
                candidate = "candidate:1 1 UDP 2122260223 192.168.1.1 5000 typ host",
                sdpMid = "0",
                sdpMLineIndex = 0,
            ),
            message,
        )
    }

    @Test
    fun `EXPECT candidate to still be valid WHEN sdpMid is missing`() {
        val message =
            parseIceCandidateMessage(
                candidate = "candidate:1 1 UDP 2122260223 192.168.1.1 5000 typ host",
                sdpMid = null,
                sdpMLineIndex = 0,
            )

        assertEquals(null, message?.sdpMid)
        assertEquals(0, message?.sdpMLineIndex)
    }

    @Test
    fun `EXPECT null WHEN candidate is missing`() {
        assertNull(parseIceCandidateMessage(candidate = null, sdpMid = "0", sdpMLineIndex = 0))
    }

    @Test
    fun `EXPECT null WHEN candidate is blank`() {
        assertNull(parseIceCandidateMessage(candidate = "   ", sdpMid = "0", sdpMLineIndex = 0))
    }

    @Test
    fun `EXPECT null WHEN sdpMLineIndex is negative`() {
        // -1 is also the agreed sentinel both platforms pass when the field is missing/unparsable.
        assertNull(
            parseIceCandidateMessage(
                candidate = "candidate:1 1 UDP 2122260223 192.168.1.1 5000 typ host",
                sdpMid = "0",
                sdpMLineIndex = -1,
            ),
        )
    }
}
