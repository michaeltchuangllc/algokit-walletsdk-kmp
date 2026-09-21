package com.michaeltchuang.walletsdk.ui.liquidStream.domain.manager

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ViewerVaultHandshakeTest {
    private val key = byteArrayOf(1, 2, 3)
    private val channel = ByteArray(32) { it.toByte() }

    @Test
    fun `EXPECT known identity to be sent before any receipt without balance or session claims`() {
        val hello = ViewerVaultHandshake("viewer", key)
        var message = ""
        assertTrue(hello.send(channel, true) { message = it; true })
        val fields = Json.parseToJsonElement(message).jsonObject
        assertEquals(setOf("type", "viewer", "viewerPublicKey", "channelId"), fields.keys)
        assertEquals("segment:handshake", fields["type"]?.jsonPrimitive?.content)
        assertEquals("viewer", fields["viewer"]?.jsonPrimitive?.content)
        assertEquals(Base64.encode(key), fields["viewerPublicKey"]?.jsonPrimitive?.content)
        assertEquals(Base64.encode(channel), fields["channelId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `EXPECT the legacy hello to be kept WHEN the channel is unknown or malformed`() {
        listOf(null, ByteArray(3)).forEach { candidate ->
            val hello = ViewerVaultHandshake("viewer", key)
            assertTrue(hello.send(candidate, true) {
                assertFalse("channelId" in Json.parseToJsonElement(it).jsonObject)
                true
            })
        }
    }

    @Test
    fun `EXPECT closed and failed sends to dedupe and resend WHEN the channel reopens or identity changes`() {
        val hello = ViewerVaultHandshake("viewer", key)
        var sent = 0
        val send: (String) -> Boolean = { sent++; true }
        assertFalse(hello.send(channel, false, sendMessage = send))
        assertEquals(0, sent)
        assertFalse(hello.send(channel, true) { false })
        assertTrue(hello.send(channel, true, sendMessage = send))
        assertFalse(hello.send(channel, true, sendMessage = send))
        assertTrue(hello.send(channel, true, force = true, sendMessage = send))
        val changed = ByteArray(32) { 42 }
        assertTrue(hello.send(changed, true, sendMessage = send))
        assertEquals(3, sent)
    }

    @Test
    fun `EXPECT identity to be advertised once known without reusing dedupe across connections`() {
        val messages = mutableListOf<String>()
        val send: (String) -> Boolean = { messages.add(it); true }
        val hello = ViewerVaultHandshake("viewer", key)
        assertTrue(hello.send(null, true, sendMessage = send))
        assertTrue(hello.send(channel, true, sendMessage = send))
        assertTrue(ViewerVaultHandshake("viewer", key).send(channel, true, sendMessage = send))
        assertEquals(3, messages.size)
    }

    @Test
    fun `EXPECT the signer snapshot to stay fixed WHEN the caller mutates its key array`() {
        val mutableKey = key.copyOf()
        val hello = ViewerVaultHandshake("viewer", mutableKey)
        mutableKey.fill(99)
        assertTrue(hello.send(channel, true) {
            val fields = Json.parseToJsonElement(it).jsonObject
            assertEquals(Base64.encode(key), fields["viewerPublicKey"]?.jsonPrimitive?.content)
            assertEquals("viewer", fields["viewer"]?.jsonPrimitive?.content)
            true
        })
    }
}
