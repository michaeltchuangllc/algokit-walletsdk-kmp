package com.michaeltchuang.walletsdk.core.railmpp.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

class InMemoryNonceStore : NonceStore {
    private val store = mutableMapOf<String, Long>()
    private val mutex = Mutex()

    override suspend fun checkAndStore(
        nonce: String,
        ttlSeconds: Int,
    ): Boolean =
        mutex.withLock {
            cleanExpired()
            if (store.containsKey(nonce)) return@withLock false
            store[nonce] = nowMs() + ttlSeconds * 1000L
            true
        }

    private fun cleanExpired() {
        val now = nowMs()
        val it = store.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.value < now) it.remove()
        }
    }

    @OptIn(ExperimentalTime::class)
    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()
}
