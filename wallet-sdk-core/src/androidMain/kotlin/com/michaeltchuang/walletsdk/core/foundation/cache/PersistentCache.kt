package com.michaeltchuang.walletsdk.core.foundation.cache

interface PersistentCache<T> {
    fun put(data: T)
    fun get(): T?
    fun clear()
}
