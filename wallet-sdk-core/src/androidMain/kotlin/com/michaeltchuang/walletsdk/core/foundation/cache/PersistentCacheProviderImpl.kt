package com.michaeltchuang.walletsdk.core.foundation.cache

import android.content.SharedPreferences
import kotlinx.serialization.KSerializer

internal class PersistentCacheProviderImpl(
    private val sharedPreferences: SharedPreferences,
) : PersistentCacheProvider {
    override fun <T : Any> getPersistentCache(
        serializer: KSerializer<T>,
        key: String,
    ): PersistentCache<T> = SharedPrefPersistentCache(serializer, key, sharedPreferences)

    override fun <T : Any> getFlowPersistentCache(
        serializer: KSerializer<T>,
        key: String,
        defaultValue: T,
    ): FlowPersistentCache<T> =
        DefaultFlowPersistentCache(
            SharedPrefPersistentCache(serializer, key, sharedPreferences),
            defaultValue,
        )
}
