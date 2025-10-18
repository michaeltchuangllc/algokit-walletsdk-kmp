package com.michaeltchuang.walletsdk.core.encryption.domain.manager

import com.michaeltchuang.walletsdk.core.foundation.AlgoKitResult
import javax.crypto.SecretKey

interface AndroidEncryptionManager {
    fun getSecretKey(): SecretKey
    suspend fun initializeEncryptionManager()
    suspend fun shouldMigrateToStrongBox(): Boolean
    suspend fun migrateToStrongBox(): AlgoKitResult<Boolean>
}
