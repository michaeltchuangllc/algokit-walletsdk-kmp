package com.michaeltchuang.walletsdk.core.passkeys.data.mapper

import com.michaeltchuang.walletsdk.core.account.data.database.model.PasskeyEntity
import com.michaeltchuang.walletsdk.core.passkeys.domain.model.AddPasskeyArgs

internal class DefaultPasskeyEntityMapper : PasskeyEntityMapper {
    override fun mapToPasskeyEntity(
        passkey: AddPasskeyArgs,
        siteId: Long,
    ): PasskeyEntity =
        PasskeyEntity(
            siteId = siteId,
            algoAddress = passkey.algoAddress,
            userId = passkey.uid,
            userName = passkey.username,
            userDisplayName = passkey.displayName,
            credentialId = passkey.credId,
            lastUsedTimeMs = null,
        )
}
