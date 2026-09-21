package com.michaeltchuang.walletsdk.ui.liquidAuth.utils

import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ChatMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HostChatRelayTest {
    private class Peer {
        val messages = mutableListOf<ChatMessage>()
    }

    private val message = ChatMessage(sender = "viewer", text = "Hello everyone", timestamp = 1L)

    @Test
    fun `EXPECT the message to reach all other viewers without echo WHEN any viewer sends`() {
        val peers = List(3) { Peer() }
        peers.forEach { sender ->
            peers.forEach { it.messages.clear() }
            relayHostChat(
                message, peers, sender,
                send = { peer, chat -> peer.messages += chat },
                onFailure = { throw it },
            )
            assertTrue(sender.messages.isEmpty())
            peers.filter { it !== sender }.forEach { assertSame(message, it.messages.single()) }
        }
    }

    @Test
    fun `EXPECT every viewer to receive host chat exactly once WHEN a duplicate peer is included`() {
        val peers = List(3) { Peer() }
        relayHostChat(
            message, peers + peers.first(),
            send = { peer, chat -> peer.messages += chat },
            onFailure = { throw it },
        )
        peers.forEach { assertSame(message, it.messages.single()) }
    }

    @Test
    fun `EXPECT other viewers to still receive the message with gift metadata preserved WHEN one viewer send fails`() {
        val peers = List(3) { Peer() }
        val gift = message.copy(amount = "1.0", asset = "USDC")
        val failures = mutableListOf<Throwable>()
        relayHostChat(
            gift, peers,
            send = { peer, chat ->
                if (peer === peers[0]) error("closed")
                peer.messages += chat
            },
            onFailure = { failures += it },
        )
        assertEquals(1, failures.size)
        peers.drop(1).forEach { assertSame(gift, it.messages.single()) }
    }

    @Test
    fun `EXPECT the new connection to still receive the message WHEN a disconnected sender shares its wallet`() {
        val oldConnection = Peer()
        val newConnection = Peer()
        relayHostChat(
            message, listOf(newConnection), oldConnection,
            send = { peer, chat -> peer.messages += chat },
            onFailure = { throw it },
        )
        assertEquals(listOf(message), newConnection.messages)
    }
}
