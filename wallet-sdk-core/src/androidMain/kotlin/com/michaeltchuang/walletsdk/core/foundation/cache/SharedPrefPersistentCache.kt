package com.michaeltchuang.walletsdk.core.foundation.cache

import android.content.SharedPreferences
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

class SharedPrefPersistentCache<T : Any>(
    private val serializer: KSerializer<T>,
    private val key: String,
    private val sharedPreferences: SharedPreferences,
    private val json: Json = Json,
) : PersistentCache<T> {
    override fun put(data: T) {
        sharedPreferences.edit().apply {
            putString(key, json.encodeToString(serializer, data))
            apply()
        }
    }

    override fun get(): T? {
        val jsonString = sharedPreferences.getString(key, null) ?: return null
        return runCatching { json.decodeFromString(serializer, jsonString) }.getOrNull()
    }

    override fun clear() {
        sharedPreferences.edit().apply {
            remove(key)
            apply()
        }
    }
}
