package com.michaeltchuang.walletsdk.core.foundation.cache

import android.content.SharedPreferences
import com.google.gson.Gson
import java.lang.reflect.Type

class SharedPrefPersistentCache<T : Any>(
    private val type: Type,
    private val key: String,
    private val sharedPreferences: SharedPreferences,
    private val gson: Gson
) : PersistentCache<T> {

    override fun put(data: T) {
        sharedPreferences.edit().apply {
            putString(key, gson.toJson(data))
            apply()
        }
    }

    override fun get(): T? {
        val json = sharedPreferences.getString(key, null).orEmpty()
        return gson.fromJson<T>(json, type)
    }

    override fun clear() {
        sharedPreferences.edit().apply {
            remove(key)
            apply()
        }
    }
}
