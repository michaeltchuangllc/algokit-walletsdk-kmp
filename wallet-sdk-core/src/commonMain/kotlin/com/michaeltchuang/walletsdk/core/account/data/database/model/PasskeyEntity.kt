package com.michaeltchuang.walletsdk.core.account.data.database.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "passkey_table",
    foreignKeys = [
        ForeignKey(
            entity = SiteEntity::class,
            parentColumns = ["id"],
            childColumns = ["site_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["site_id"]),
        Index(value = ["algo_address"])
    ]
)
internal data class PasskeyEntity(
    @PrimaryKey
    @ColumnInfo(name = "credential_id")
    val credentialId: String,
    @ColumnInfo(name = "site_id")
    val siteId: Long,
    @ColumnInfo(name = "algo_address")
    val algoAddress: String,
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "user_name")
    val userName: String,
    @ColumnInfo(name = "user_display_name")
    val userDisplayName: String?,
    @ColumnInfo(name = "last_used_time_ms")
    val lastUsedTimeMs: Long?
)
