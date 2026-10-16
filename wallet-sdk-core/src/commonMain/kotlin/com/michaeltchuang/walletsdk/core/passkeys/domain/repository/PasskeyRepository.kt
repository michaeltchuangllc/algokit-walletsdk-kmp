package com.michaeltchuang.walletsdk.core.passkeys.domain.repository

import com.michaeltchuang.walletsdk.core.passkeys.domain.model.AddPasskeyArgs
import com.michaeltchuang.walletsdk.core.passkeys.domain.model.Passkey
import kotlinx.coroutines.flow.Flow

interface PasskeyRepository {
    fun getAllPasskeysAsFlow(): Flow<List<Passkey>>

    suspend fun getSitePasskeysCount(url: String): Int

    suspend fun getSitePasskeys(url: String): List<Passkey>

    suspend fun addNewPasskey(args: AddPasskeyArgs)

    suspend fun getPasskey(credId: String): Passkey?

    suspend fun getCredentialIdBySiteId(url: String): String?

    suspend fun getCredentialIdByAddress(address: String): String?

    suspend fun removePasskeyByCredentialId(credId: String)

    suspend fun removePasskeyByAddress(address: String)

    suspend fun clearAllPasskeys()

    suspend fun setPasskeyLastUsedTime(
        credId: String,
        lastUsed: Long,
    )

    suspend fun doesPasskeyExist(
        rpId: String,
        username: String,
        address: String,
    ): Boolean
}
