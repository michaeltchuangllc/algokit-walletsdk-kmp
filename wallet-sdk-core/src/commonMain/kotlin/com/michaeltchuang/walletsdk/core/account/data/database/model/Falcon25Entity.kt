package com.michaeltchuang.walletsdk.core.account.data.database.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "falcon_25")
internal data class Falcon25Entity(
    @PrimaryKey @ColumnInfo("algo_address") val algoAddress: String,
    @ColumnInfo("public_key", typeAffinity = ColumnInfo.BLOB) val publicKey: ByteArray,
    @ColumnInfo("encrypted_private_key", typeAffinity = ColumnInfo.BLOB) val encryptedPrivateKey: ByteArray,
    @ColumnInfo("encrypted_entropy", typeAffinity = ColumnInfo.BLOB) val encryptedEntropy: ByteArray,
    @ColumnInfo("encrypted_seed", typeAffinity = ColumnInfo.BLOB) val encryptedSeed: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is Falcon25Entity &&
            algoAddress == other.algoAddress &&
            publicKey.contentEquals(other.publicKey) &&
            encryptedPrivateKey.contentEquals(other.encryptedPrivateKey) &&
            encryptedEntropy.contentEquals(other.encryptedEntropy) &&
            encryptedSeed.contentEquals(other.encryptedSeed)

    override fun hashCode(): Int {
        var result = algoAddress.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + encryptedPrivateKey.contentHashCode()
        result = 31 * result + encryptedEntropy.contentHashCode()
        result = 31 * result + encryptedSeed.contentHashCode()
        return result
    }
}
