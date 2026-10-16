package com.michaeltchuang.walletsdk.ui.liquidAuth.service

import com.michaeltchuang.walletsdk.core.railmpp.MppNetworks
import com.michaeltchuang.walletsdk.core.railmpp.core.readMppNetwork
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LiquidAuthHostTransportMessageTest {
    @Test
    fun `EXPECT the legacy handshake to still parse WHEN no channel is present`() {
        val parsed =
            parseLiquidAuthHostTransportMessage(
                """{"type":"segment:handshake","viewer":"viewer-address","viewerPublicKey":"AQID"}""",
            )

        val hello = assertNotNull(parsed.viewerHello)
        assertEquals("viewer-address", hello.viewerAddress)
        assertContentEquals(byteArrayOf(1, 2, 3), hello.viewerPublicKey)
        assertNull(hello.channelId)
        assertNull(hello.network)
        assertNull(parsed.paymentVoucher)
    }

    @Test
    fun `EXPECT no voucher or session id to be required WHEN the handshake carries a channel hint`() {
        val parsed =
            parseLiquidAuthHostTransportMessage(
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
            val parsed =
                parseLiquidAuthHostTransportMessage(
                    """{"type":"segment:handshake","viewer":"viewer-address","viewerPublicKey":"AQID","channelId":$channel}""",
                )

            val hello = assertNotNull(parsed.viewerHello)
            assertEquals("viewer-address", hello.viewerAddress)
            assertContentEquals(byteArrayOf(1, 2, 3), hello.viewerPublicKey)
            assertNull(hello.channelId)
        }
    }

    @Test
    fun `EXPECT handshake to accept canonical legacy and matching both-key networks`() {
        val network = MppNetworks.SOLANA_DEVNET
        val compatibleFields =
            listOf(
                """"mppNetwork":"$network"""",
                """"network":"$network"""",
                """"mppNetwork":"$network","network":"$network"""",
            )
        for (fields in compatibleFields) {
            val message = """{"type":"segment:handshake","viewer":"viewer-address","viewerPublicKey":"AQID",$fields}"""
            val hello = assertNotNull(parseLiquidAuthHostTransportMessage(message).viewerHello)
            assertEquals(network, hello.network)
            assertEquals("viewer-address", extractLiquidAuthViewerAddress(message))
        }
    }

    @Test
    fun `EXPECT invalid explicit networks to fail parsing without hiding viewer identity`() {
        val network = MppNetworks.SOLANA_DEVNET
        val invalidFields =
            listOf(
                """"mppNetwork":"$network","network":"${MppNetworks.ALGORAND_TESTNET}"""",
                """"mppNetwork":null,"network":"$network"""",
                """"mppNetwork":"","network":"$network"""",
                """"mppNetwork":42,"network":"$network"""",
                """"mppNetwork":{},"network":"$network"""",
                """"mppNetwork":"$network","network":false""",
                """"network":[]""",
            )
        for (fields in invalidFields) {
            val message = """{"type":"segment:handshake","viewer":"viewer-address",$fields}"""
            assertFailsWith<IllegalArgumentException> { parseLiquidAuthHostTransportMessage(message) }
            assertEquals("viewer-address", extractLiquidAuthViewerAddress(message))
        }
    }

    @Test
    fun `EXPECT identity admission fallback only when both network keys are absent`() {
        val network = MppNetworks.SOLANA_DEVNET
        val address = "11111111111111111111111111111111"
        val identities =
            listOf(
                """"type":"credential","address":"$address"""",
                """"type":"segment:handshake","viewer":"$address"""",
                """"type":"segment:voucher","viewer":"$address"""",
            )
        val compatibleFields =
            listOf(
                "",
                ""","mppNetwork":"$network"""",
                ""","network":"$network"""",
                ""","mppNetwork":"$network","network":"$network"""",
            )
        val invalidFields =
            listOf(
                """"mppNetwork":null""",
                """"network":{}""",
                """"mppNetwork":"$network","network":"${MppNetworks.SOLANA_TESTNET}"""",
            )
        for (identity in identities) {
            for (fields in compatibleFields) {
                val message = """{$identity$fields}"""
                assertEquals(address, extractLiquidAuthViewerAddress(message))
                validateLiquidStreamViewerNetwork(
                    network,
                    address,
                    Json.parseToJsonElement(message).jsonObject.readMppNetwork() ?: network,
                )
            }
            for (fields in invalidFields) {
                val message = """{$identity,$fields}"""
                assertEquals(address, extractLiquidAuthViewerAddress(message))
                assertFailsWith<IllegalArgumentException> {
                    validateLiquidStreamViewerNetwork(
                        network,
                        address,
                        Json.parseToJsonElement(message).jsonObject.readMppNetwork() ?: network,
                    )
                }
            }
        }
    }

    @Test
    fun `EXPECT malformed handshake JSON to throw while identity remains available for rejection`() {
        val message = """{"type":"segment:handshake","viewer":"viewer-address","mppNetwork":"""
        assertEquals("viewer-address", extractLiquidAuthViewerAddress(message))
        assertFailsWith<IllegalArgumentException> { parseLiquidAuthHostTransportMessage(message) }
        assertNull(extractLiquidAuthViewerAddress("""{"reference":"ping","address":"not-an-identity"}"""))
    }
}
