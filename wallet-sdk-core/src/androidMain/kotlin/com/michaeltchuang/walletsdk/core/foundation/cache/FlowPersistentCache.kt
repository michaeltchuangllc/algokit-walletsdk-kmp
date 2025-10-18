package com.michaeltchuang.walletsdk.core.foundation.cache

import kotlinx.coroutines.flow.StateFlow

interface FlowPersistentCache<T : Any> : PersistentCache<T> {
    fun observe(): StateFlow<T>
    override fun get(): T
}
