
package com.michaeltchuang.walletsdk.core.passkeys.data.mapper

import com.michaeltchuang.walletsdk.core.account.data.database.model.PasskeyEntity
import com.michaeltchuang.walletsdk.core.passkeys.model.AddPasskeyArgs


internal class DefaultPasskeyEntityMapper() : PasskeyEntityMapper {

    override fun mapToPasskeyEntity(passkey: AddPasskeyArgs, siteId: Long): PasskeyEntity {
        return PasskeyEntity(
            siteId = siteId,
            algoAddress = passkey.bip44Address,
            userId = passkey.uid,
            userName = passkey.username,
            userDisplayName = passkey.displayName,
            credentialId = passkey.credId,
            lastUsedTimeMs = null
        )
    }
}
