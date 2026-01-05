package com.michaeltchuang.walletsdk.core.passkeys.data.mapper

import com.michaeltchuang.walletsdk.core.account.data.database.model.PasskeyEntity
import com.michaeltchuang.walletsdk.core.account.data.database.model.SiteEntity
import com.michaeltchuang.walletsdk.core.passkeys.model.Passkey
import com.michaeltchuang.walletsdk.core.passkeys.model.PasskeySite

internal class DefaultPasskeyMapper : PasskeyMapper {
    override fun mapToPasskey(
        entity: PasskeyEntity,
        siteEntity: SiteEntity,
    ): Passkey =
        Passkey(
            algoAddress = entity.algoAddress,
            userId = entity.userId,
            username = entity.userName,
            displayName = entity.userDisplayName ?: entity.userName,
            credId = entity.credentialId,
            lastUsed = entity.lastUsedTimeMs,
            site = PasskeySite(id = siteEntity.id, url = siteEntity.url, name = siteEntity.name),
        )
}
