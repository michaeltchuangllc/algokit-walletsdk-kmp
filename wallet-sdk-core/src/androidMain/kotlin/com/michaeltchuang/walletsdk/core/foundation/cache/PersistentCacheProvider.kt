package com.michaeltchuang.walletsdk.core.foundation.cache

import java.lang.reflect.Type

interface PersistentCacheProvider {
    fun <T : Any> getPersistentCache(type: Type, key: String): PersistentCache<T>
    fun <T : Any> getFlowPersistentCache(
        type: Type,
        key: String,
        defaultValue: T
    ): FlowPersistentCache<T>
}
