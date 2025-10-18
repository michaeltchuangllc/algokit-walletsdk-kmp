package com.michaeltchuang.walletsdk.core.foundation.cache

import android.content.SharedPreferences
import com.google.gson.Gson
import java.lang.reflect.Type

internal class PersistentCacheProviderImpl(
    private val sharedPreferences: SharedPreferences,
    private val gson: Gson
) : PersistentCacheProvider {

    override fun <T : Any> getPersistentCache(type: Type, key: String): PersistentCache<T> {
        return SharedPrefPersistentCache(type, key, sharedPreferences, gson)
    }

    override fun <T : Any> getFlowPersistentCache(
        type: Type,
        key: String,
        defaultValue: T
    ): FlowPersistentCache<T> {
        return DefaultFlowPersistentCache(
            SharedPrefPersistentCache(type, key, sharedPreferences, gson),
            defaultValue
        )
    }
}
