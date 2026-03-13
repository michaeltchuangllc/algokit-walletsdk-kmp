package com.michaeltchuang.walletsdk.core.account.domain.model.local

sealed interface LocalAccount {
    val algoAddress: String

    data class HdKey(
        override val algoAddress: String,
        val publicKey: ByteArray,
        val seedId: Int,
        val account: Int,
        val change: Int,
        val keyIndex: Int,
        val derivationType: Int,
    ) : LocalAccount {
        override fun equals(other: Any?): Boolean =
            other is HdKey &&
                algoAddress == other.algoAddress &&
                publicKey.contentEquals(other.publicKey) &&
                seedId == other.seedId &&
                account == other.account &&
                change == other.change &&
                keyIndex == other.keyIndex &&
                derivationType == other.derivationType

        override fun hashCode(): Int =
            algoAddress.hashCode() + publicKey.contentHashCode() + seedId + account + change + keyIndex + derivationType
    }

    data class Algo25(
        override val algoAddress: String,
    ) : LocalAccount

    data class Falcon24(
        override val algoAddress: String,
        val seedId: Int,
        val publicKey: ByteArray,
    ) : LocalAccount {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false

            other as Falcon24

            if (seedId != other.seedId) return false
            if (algoAddress != other.algoAddress) return false
            if (!publicKey.contentEquals(other.publicKey)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = seedId
            result = 31 * result + algoAddress.hashCode()
            result = 31 * result + publicKey.contentHashCode()
            return result
        }
    }

    data class LedgerBle(
        override val algoAddress: String,
        val deviceMacAddress: String,
        val bluetoothName: String?,
        val indexInLedger: Int,
    ) : LocalAccount

    data class NoAuth(
        override val algoAddress: String,
    ) : LocalAccount
    
    data class SeedVault(
        override val algoAddress: String,
        val chainId: String,
    ) : LocalAccount
}
