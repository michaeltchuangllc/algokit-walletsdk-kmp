package com.michaeltchuang.walletsdk.core.liquidAuth.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HostPeerRegistryTest {
    private class Peer

    @Test
    fun joiningSecondViewerPreservesFirstConnection() {
        val registry = HostPeerRegistry<Peer>()
        val first = Peer()
        val second = Peer()
        registry.add("invitation-a", first)

        assertTrue(registry.add("invitation-b", second))

        assertSame(first, registry.snapshot["invitation-a"])
        assertSame(second, registry.snapshot["invitation-b"])
        assertEquals(2, registry.snapshot.size)
    }

    @Test
    fun duplicateInvitationCannotReplaceExistingViewer() {
        val registry = HostPeerRegistry<Peer>()
        val first = Peer()
        registry.add("invitation-a", first)

        assertFalse(registry.add("invitation-a", Peer()))

        assertSame(first, registry.snapshot["invitation-a"])
    }

    @Test
    fun leavingSecondViewerDoesNotRemoveFirstViewer() {
        val registry = HostPeerRegistry<Peer>()
        val first = Peer()
        val second = Peer()
        registry.add("invitation-a", first)
        registry.add("invitation-b", second)

        assertSame(second, registry.remove("invitation-b"))

        assertSame(first, registry.snapshot["invitation-a"])
        assertEquals(1, registry.snapshot.size)
        assertFalse(registry.contains("invitation-b", second))
    }

    @Test
    fun staleSessionCannotActOnReplacement() {
        val registry = HostPeerRegistry<Peer>()
        val old = Peer()
        val replacement = Peer()
        registry.add("invitation-a", old)
        registry.remove("invitation-a")
        registry.add("invitation-a", replacement)

        assertFalse(registry.contains("invitation-a", old))
        assertTrue(registry.contains("invitation-a", replacement))
    }

    @Test
    fun hostShutdownDrainsEveryPeerExactlyOnce() {
        val registry = HostPeerRegistry<Peer>()
        val first = Peer()
        val second = Peer()
        registry.add("invitation-a", first)
        registry.add("invitation-b", second)

        assertEquals(listOf(first, second), registry.drain())
        assertTrue(registry.snapshot.isEmpty())
        assertTrue(registry.drain().isEmpty())
    }
}
