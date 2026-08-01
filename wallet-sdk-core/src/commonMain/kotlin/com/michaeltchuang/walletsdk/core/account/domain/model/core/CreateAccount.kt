package com.michaeltchuang.walletsdk.core.account.domain.model.core

data class CreateAccount(
    val address: String,
    var customName: String?,
    var orderIndex: Int,
    val isBackedUp: Boolean,
    val type: Type,
) {
    sealed interface Type {
        data class HdKey(
            val publicKey: ByteArray,
            val encryptedPrivateKey: ByteArray,
            val encryptedEntropy: ByteArray,
            val account: Int,
            val change: Int,
            val keyIndex: Int,
            val derivationType: Int,
        ) : Type

        data class Algo25(
            val encryptedSecretKey: ByteArray,
        ) : Type

        data class Falcon25(
            val publicKey: ByteArray,
            val encryptedPrivateKey: ByteArray,
            val encryptedEntropy: ByteArray,
        ) : Type

        data class Falcon24(
            val publicKey: ByteArray,
            val encryptedPrivateKey: ByteArray,
            val encryptedEntropy: ByteArray,
        ) : Type

        data class LedgerBle(
            val deviceMacAddress: String,
            val indexInLedger: Int,
            val bluetoothName: String?,
        ) : Type

        data object NoAuth : Type
    }
}
