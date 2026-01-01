package com.michaeltchuang.walletsdk.core.account.domain.usecase.recoverypassphrase

import com.michaeltchuang.walletsdk.core.account.data.mapper.model.RegisteredHdKeyItemMapper
import com.michaeltchuang.walletsdk.core.account.domain.model.local.RegisteredHdKeyItem
import com.michaeltchuang.walletsdk.core.account.domain.repository.local.RecoverRegisteredAccountsAccountProcessor
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetRegisteredHdKeys
import com.michaeltchuang.walletsdk.core.encryption.decryptByteArray
import com.michaeltchuang.walletsdk.core.foundation.utils.clearFromMemory

internal class DefaultRecoverRegisteredAccountsAccountProcessor(
    private val getRegisteredHdKeys: GetRegisteredHdKeys,
    private val registeredHdKeyItemMapper: RegisteredHdKeyItemMapper,
) : RecoverRegisteredAccountsAccountProcessor {
    override suspend fun getRegisteredHdKeyItems(encryptedEntropy: ByteArray): List<RegisteredHdKeyItem> {
        val entropy = decryptByteArray(encryptedEntropy)
        val registeredAccounts = getRegisteredHdKeys(entropy.copyOf())
        entropy.clearFromMemory()
        return registeredAccounts.map { hdKey ->
            registeredHdKeyItemMapper(
                hdKey,
            )
        }
    }
}
