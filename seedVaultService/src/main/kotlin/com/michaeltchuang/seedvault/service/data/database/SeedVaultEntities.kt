package com.michaeltchuang.seedvault.service.data.database

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/**
 * A seed in the vault. The Falcon-1024 entropy behind the 25-word seed phrase and the private key
 * derived from it are stored encrypted with the wallet-sdk-core Android Keystore key. The address
 * and public key are not secret and are stored as is. The address is unique, so the same seed
 * phrase is only stored once.
 */
@Entity(tableName = "seed", indices = [Index(value = ["address"], unique = true)])
internal class SeedEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo("name") val name: String,
    @ColumnInfo("is_backed_up") val isBackedUp: Boolean,
    @ColumnInfo("encrypted_entropy", typeAffinity = ColumnInfo.BLOB) val encryptedEntropy: ByteArray,
    @ColumnInfo("address") val address: String,
    @ColumnInfo("public_key", typeAffinity = ColumnInfo.BLOB) val publicKey: ByteArray,
    @ColumnInfo("encrypted_private_key", typeAffinity = ColumnInfo.BLOB) val encryptedPrivateKey: ByteArray,
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
