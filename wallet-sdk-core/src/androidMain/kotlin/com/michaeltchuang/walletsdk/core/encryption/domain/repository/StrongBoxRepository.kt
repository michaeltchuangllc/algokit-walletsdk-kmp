package com.michaeltchuang.walletsdk.core.encryption.domain.repository

internal interface StrongBoxRepository {
    suspend fun saveStrongBoxUsed(check: Boolean)
    suspend fun getStrongBoxUsed(): Boolean
}
