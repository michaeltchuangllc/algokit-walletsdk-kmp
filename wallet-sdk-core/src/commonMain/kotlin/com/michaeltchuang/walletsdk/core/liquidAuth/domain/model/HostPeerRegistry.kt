package com.michaeltchuang.walletsdk.core.liquidAuth.domain.model

/**
 * Broadcast membership, confined to the host's dispatcher.
 *
 * Invitation IDs are single-use peer IDs, never wallet addresses. Removing a member does not
 * close any other member or the shared capture; resource ownership stays with the caller.
 */
class HostPeerRegistry<T> {
    private val entries = linkedMapOf<String, T>()

    val snapshot: Map<String, T> get() = entries.toMap()

    fun add(
        id: String,
        peer: T,
    ): Boolean {
        require(id.isNotBlank())
        if (entries.containsKey(id)) return false
        entries[id] = peer
        return true
    }

    fun contains(
        id: String,
        peer: T,
    ): Boolean = entries[id] === peer

    fun remove(id: String): T? = entries.remove(id)

    fun drain(): List<T> = entries.values.toList().also { entries.clear() }
}
