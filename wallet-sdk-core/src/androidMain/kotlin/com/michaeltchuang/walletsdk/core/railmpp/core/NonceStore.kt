package com.michaeltchuang.walletsdk.core.railmpp.core

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory nonce store for replay protection.
 * Nonces expire after the given TTL.
 */
class InMemoryNonceStore : NonceStore {
    private val store = ConcurrentHashMap<String, Long>()

    override suspend fun checkAndStore(nonce: String, ttlSeconds: Int): Boolean {
        cleanExpired()
        if (store.containsKey(nonce)) return false
        store[nonce] = System.currentTimeMillis() + ttlSeconds * 1000L
        return true
    }

    private fun cleanExpired() {
        val now = System.currentTimeMillis()
        val it = store.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.value < now) it.remove()
        }
    }
}
