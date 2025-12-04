package com.michaeltchuang.walletsdk.core.account.data.database.model

import androidx.room.Embedded
import androidx.room.Relation

internal data class SiteWithPasskeysQuery(
    @Embedded val site: SiteEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "site_id"
    )
    val passkeys: List<PasskeyEntity>
)
