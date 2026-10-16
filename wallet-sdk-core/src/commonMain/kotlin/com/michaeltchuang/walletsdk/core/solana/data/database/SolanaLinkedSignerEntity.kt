package com.michaeltchuang.walletsdk.core.solana.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import com.michaeltchuang.walletsdk.core.account.data.database.model.SeedVaultEntity

/**
 * A device-generated session key linked to a Seed Vault account so vouchers can be signed
 * without prompting the Seed Vault every time. Deleted automatically with its Seed Vault account.
 */
@Entity(
    tableName = "solana_linked_signer",
    foreignKeys = [
        ForeignKey(
            entity = SeedVaultEntity::class,
            parentColumns = ["public_key"],
            childColumns = ["owner_address"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class SolanaLinkedSignerEntity(
    @PrimaryKey
    @ColumnInfo("owner_address")
    val ownerAddress: String,
    @ColumnInfo("signer_address")
    val signerAddress: String,
    @ColumnInfo("encrypted_private_seed", typeAffinity = ColumnInfo.BLOB)
    val encryptedPrivateSeed: ByteArray,
    @ColumnInfo("source")
    val source: String,
    @ColumnInfo("derivation_path")
    val derivationPath: String?,
    @ColumnInfo("created_at_ms")
    val createdAtMs: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is SolanaLinkedSignerEntity &&
            ownerAddress == other.ownerAddress &&
            signerAddress == other.signerAddress &&
            encryptedPrivateSeed.contentEquals(other.encryptedPrivateSeed) &&
            source == other.source &&
            derivationPath == other.derivationPath &&
            createdAtMs == other.createdAtMs

    override fun hashCode(): Int {
        var result = ownerAddress.hashCode()
        result = 31 * result + signerAddress.hashCode()
        result = 31 * result + encryptedPrivateSeed.contentHashCode()
        result = 31 * result + source.hashCode()
        result = 31 * result + (derivationPath?.hashCode() ?: 0)
        result = 31 * result + createdAtMs.hashCode()
        return result
    }
}
