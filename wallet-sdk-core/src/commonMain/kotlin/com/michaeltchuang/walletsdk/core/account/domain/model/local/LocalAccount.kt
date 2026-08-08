package com.michaeltchuang.walletsdk.core.account.domain.model.local

sealed interface LocalAccount {
    val address: String

    data class HdKey(
        override val address: String,
        val publicKey: ByteArray,
        val seedId: Int,
        val account: Int,
        val change: Int,
        val keyIndex: Int,
        val derivationType: Int,
    ) : LocalAccount {
        override fun equals(other: Any?): Boolean =
            other is HdKey &&
                address == other.address &&
                publicKey.contentEquals(other.publicKey) &&
                seedId == other.seedId &&
                account == other.account &&
                change == other.change &&
                keyIndex == other.keyIndex &&
                derivationType == other.derivationType

        override fun hashCode(): Int =
            address.hashCode() + publicKey.contentHashCode() + seedId + account + change + keyIndex + derivationType
    }

    data class Algo25(
        override val address: String,
    ) : LocalAccount

    data class Falcon25(
        override val address: String,
        val publicKey: ByteArray,
    ) : LocalAccount {
        override fun equals(other: Any?): Boolean =
            other is Falcon25 && address == other.address && publicKey.contentEquals(other.publicKey)

        override fun hashCode(): Int = 31 * address.hashCode() + publicKey.contentHashCode()
    }

    data class Falcon24(
        override val address: String,
        val seedId: Int,
        val publicKey: ByteArray,
    ) : LocalAccount {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false

            other as Falcon24

            if (seedId != other.seedId) return false
            if (address != other.address) return false
            if (!publicKey.contentEquals(other.publicKey)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = seedId
            result = 31 * result + address.hashCode()
            result = 31 * result + publicKey.contentHashCode()
            return result
        }
    }

    data class LedgerBle(
        override val address: String,
        val deviceMacAddress: String,
        val bluetoothName: String?,
        val indexInLedger: Int,
    ) : LocalAccount

    data class NoAuth(
        override val address: String,
    ) : LocalAccount

    data class SeedVault(
        override val address: String,
        val publicKey: String,
        val chainId: String,
        val accountName: String? = null,
    ) : LocalAccount
}
