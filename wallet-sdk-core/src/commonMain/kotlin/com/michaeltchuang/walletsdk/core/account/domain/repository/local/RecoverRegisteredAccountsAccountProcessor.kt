package com.michaeltchuang.walletsdk.core.account.domain.repository.local

import com.michaeltchuang.walletsdk.core.account.domain.model.local.RegisteredHdKeyItem

interface RecoverRegisteredAccountsAccountProcessor {
    suspend fun getRegisteredHdKeyItems(encryptedEntropy: ByteArray): List<RegisteredHdKeyItem>
}