package com.michaeltchuang.walletsdk.core.account.data.database.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity for storing Seed Vault accounts in the local database.
 * Contains public_key as primary key (address), address string, chainId, and optional accountName.
 */
@Entity(tableName = "seed_vault")
internal data class SeedVaultEntity(
    @PrimaryKey
    @ColumnInfo("public_key")
    val publicKey: String,
    @ColumnInfo("address")
    val address: String,
    @ColumnInfo("chainId")
    val chainId: String,
    @ColumnInfo("account_name")
    val accountName: String?,
)
