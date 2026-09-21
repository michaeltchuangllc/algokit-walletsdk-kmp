package com.michaeltchuang.walletsdk.ui.liquidAuth.service

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LiquidAuthHostTransportMessageTest {
    @Test
    fun `EXPECT the legacy handshake to still parse WHEN no channel is present`() {
        val parsed = parseLiquidAuthHostTransportMessage(
            """{"type":"segment:handshake","viewer":"viewer-address","viewerPublicKey":"AQID"}""",
        )

        val hello = assertNotNull(parsed.viewerHello)
        assertEquals("viewer-address", hello.viewerAddress)
        assertContentEquals(byteArrayOf(1, 2, 3), hello.viewerPublicKey)
        assertNull(hello.channelId)
        assertNull(parsed.paymentVoucher)
    }

    @Test
    fun `EXPECT no voucher or session id to be required WHEN the handshake carries a channel hint`() {
        val parsed = parseLiquidAuthHostTransportMessage(
            """{"type":"segment:handshake","viewer":"viewer-address","viewerPublicKey":"AQID","channelId":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="}""",
        )

        val hello = assertNotNull(parsed.viewerHello)
        assertContentEquals(ByteArray(32), hello.channelId)
        assertContentEquals(byteArrayOf(1, 2, 3), hello.viewerPublicKey)
        assertNull(parsed.paymentVoucher)
    }

    @Test
    fun `EXPECT the channel hint to stay null without dropping legacy fields WHEN it is invalid or empty`() {
        for (channel in listOf("\"%%%\"", "\"\"", "null")) {
            val parsed = parseLiquidAuthHostTransportMessage(
                """{"type":"segment:handshake","viewer":"viewer-address","viewerPublicKey":"AQID","channelId":$channel}""",
            )

            val hello = assertNotNull(parsed.viewerHello)
            assertEquals("viewer-address", hello.viewerAddress)
            assertContentEquals(byteArrayOf(1, 2, 3), hello.viewerPublicKey)
            assertNull(hello.channelId)
        }
    }
}
