package com.michaeltchuang.walletsdk.seedvault.service.data.database

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import androidx.room.Relation

/**
 * A seed in the vault. Only the Falcon-1024 entropy behind the 25-word seed phrase is stored, and
 * only after it has been encrypted with the wallet-sdk-core Android Keystore key.
 */
@Entity(tableName = "seed")
internal class SeedEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo("name") val name: String,
    @ColumnInfo("is_backed_up") val isBackedUp: Boolean,
    @ColumnInfo("encrypted_entropy", typeAffinity = ColumnInfo.BLOB) val encryptedEntropy: ByteArray,
)

@Entity(
    tableName = "authorized_app",
    primaryKeys = ["seed_id", "package_name"],
    foreignKeys = [
        ForeignKey(
            entity = SeedEntity::class,
            parentColumns = ["id"],
            childColumns = ["seed_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class AuthorizedAppEntity(
    @ColumnInfo("seed_id") val seedId: Long,
    @ColumnInfo("package_name") val packageName: String,
    @ColumnInfo("label") val label: String,
    @ColumnInfo("is_privileged") val isPrivileged: Boolean,
)

internal class SeedWithApps(
    @Embedded val seed: SeedEntity,
    @Relation(parentColumn = "id", entityColumn = "seed_id") val apps: List<AuthorizedAppEntity>,
)
