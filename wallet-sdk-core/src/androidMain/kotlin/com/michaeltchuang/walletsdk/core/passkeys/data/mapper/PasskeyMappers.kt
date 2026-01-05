package com.michaeltchuang.walletsdk.core.passkeys.data.mapper

import com.michaeltchuang.walletsdk.core.account.data.database.model.PasskeyEntity
import com.michaeltchuang.walletsdk.core.account.data.database.model.SiteEntity
import com.michaeltchuang.walletsdk.core.passkeys.model.AddPasskeyArgs
import com.michaeltchuang.walletsdk.core.passkeys.model.Passkey

internal interface PasskeyMapper {
    fun mapToPasskey(
        entity: PasskeyEntity,
        siteEntity: SiteEntity,
    ): Passkey
}

internal interface PasskeyEntityMapper {
    fun mapToPasskeyEntity(
        passkey: AddPasskeyArgs,
        siteId: Long,
    ): PasskeyEntity
}
