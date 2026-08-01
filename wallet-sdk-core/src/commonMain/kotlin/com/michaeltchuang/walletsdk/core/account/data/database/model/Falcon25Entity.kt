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
) {
    override fun equals(other: Any?): Boolean =
        other is Falcon25Entity &&
            algoAddress == other.algoAddress &&
            publicKey.contentEquals(other.publicKey) &&
            encryptedPrivateKey.contentEquals(other.encryptedPrivateKey) &&
            encryptedEntropy.contentEquals(other.encryptedEntropy)

    override fun hashCode(): Int =
        31 *
            (31 *
                (31 * algoAddress.hashCode() + publicKey.contentHashCode()) +
                encryptedPrivateKey.contentHashCode()) +
            encryptedEntropy.contentHashCode()
}
