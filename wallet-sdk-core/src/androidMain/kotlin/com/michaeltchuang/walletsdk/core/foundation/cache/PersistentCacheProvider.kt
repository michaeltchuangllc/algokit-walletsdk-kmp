package com.michaeltchuang.walletsdk.core.foundation.cache

import kotlinx.serialization.KSerializer

interface PersistentCacheProvider {
    fun <T : Any> getPersistentCache(
        serializer: KSerializer<T>,
        key: String,
    ): PersistentCache<T>

    fun <T : Any> getFlowPersistentCache(
        serializer: KSerializer<T>,
        key: String,
        defaultValue: T,
    ): FlowPersistentCache<T>
}
