package com.michaeltchuang.walletsdk.core.encryption.data.repository

import com.michaeltchuang.walletsdk.core.encryption.domain.repository.StrongBoxRepository
import com.michaeltchuang.walletsdk.core.foundation.cache.PersistentCache


class StrongBoxRepositoryImpl(
    private val strongBoxUsedStorage: PersistentCache<Boolean>,
) : StrongBoxRepository {

    override suspend fun saveStrongBoxUsed(check: Boolean) {
        strongBoxUsedStorage.put(check)
    }

    override suspend fun getStrongBoxUsed(): Boolean {
        return strongBoxUsedStorage.get() ?: false
    }
}
