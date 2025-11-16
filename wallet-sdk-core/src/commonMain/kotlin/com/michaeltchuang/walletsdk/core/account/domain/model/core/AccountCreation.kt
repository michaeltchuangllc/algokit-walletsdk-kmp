package com.michaeltchuang.walletsdk.core.account.domain.model.core

import com.michaeltchuang.walletsdk.core.foundation.utils.CreationType

data class AccountCreation(
    val address: String,
    var customName: String?,
    var orderIndex: Int = Int.MAX_VALUE,
    val isBackedUp: Boolean,
    val type: Type,
    val creationType: CreationType,
    val isRecoverRegisteredAccount: Boolean=false
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
            val seedId: Int? = null,
        ) : Type

        data class Falcon24(
            val publicKey: ByteArray,
            val encryptedPrivateKey: ByteArray,
            val encryptedEntropy: ByteArray,
            val seedId: Int? = null,
        ) : Type

        data class Algo25(
            val encryptedSecretKey: ByteArray,
        ) : Type

        data class LedgerBle(
            val deviceMacAddress: String,
            val indexInLedger: Int,
            val bluetoothName: String?,
        ) : Type

        data object NoAuth : Type
    }

    fun toCreateAccount(): CreateAccount =
        CreateAccount(
            address = address,
            customName = customName,
            orderIndex = orderIndex,
            isBackedUp = isBackedUp,
            type =
                when (type) {
                    is Type.HdKey ->
                        CreateAccount.Type.HdKey(
                            type.publicKey,
                            type.encryptedPrivateKey,
                            type.encryptedEntropy,
                            type.account,
                            type.change,
                            type.keyIndex,
                            type.derivationType,
                        )

                    is Type.Algo25 ->
                        CreateAccount.Type.Algo25(
                            type.encryptedSecretKey,
                        )

                    is Type.Falcon24 ->
                        CreateAccount.Type.Falcon24(
                            publicKey = type.publicKey,
                            encryptedPrivateKey = type.encryptedPrivateKey,
                            encryptedEntropy = type.encryptedEntropy,
                        )

                    is Type.LedgerBle ->
                        CreateAccount.Type.LedgerBle(
                            type.deviceMacAddress,
                            type.indexInLedger,
                            type.bluetoothName,
                        )

                    is Type.NoAuth -> CreateAccount.Type.NoAuth
                },
        )
}
