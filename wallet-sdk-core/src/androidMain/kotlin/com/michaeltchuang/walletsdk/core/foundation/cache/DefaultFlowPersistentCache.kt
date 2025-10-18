package com.michaeltchuang.walletsdk.core.foundation.cache


import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DefaultFlowPersistentCache<T : Any>(
    private val persistentCache: PersistentCache<T>,
    private val initialValue: T
) : FlowPersistentCache<T>, PersistentCache<T> by persistentCache {

    private val cacheFlow = MutableStateFlow(get())

    override fun put(data: T) {
        persistentCache.put(data)
        cacheFlow.value = data
    }

    override fun clear() {
        persistentCache.clear()
        cacheFlow.value = initialValue
    }

    override fun observe(): StateFlow<T> {
        return cacheFlow.asStateFlow()
    }

    override fun get(): T {
        return persistentCache.get() ?: initialValue
    }
}
