package com.michaeltchuang.walletsdk.core.network.model

sealed interface TransactionSigner {
    val address: String

    data class LedgerBle(
        override val address: String,
        val bluetoothAddress: String,
        val positionInLedger: Int = 0,
    ) : TransactionSigner

    data class Algo25(
        override val address: String,
    ) : TransactionSigner

    data class HdKey(
        override val address: String,
    ) : TransactionSigner

    data class Falcon25(
        override val address: String,
    ) : TransactionSigner

    data class Falcon24(
        override val address: String,
    ) : TransactionSigner

    sealed interface SignerNotFound : TransactionSigner {
        data class NoAuth(
            override val address: String,
        ) : SignerNotFound

        data class Rekeyed(
            override val address: String,
        ) : SignerNotFound

        data class AccountNotFound(
            override val address: String,
        ) : SignerNotFound

        data class AuthAccountIsNoAuth(
            override val address: String,
        ) : SignerNotFound

        data class AuthAccountSigningDetailsNotFound(
            override val address: String,
            val authAddress: String,
        ) : SignerNotFound

        data class AuthAddressNotFound(
            override val address: String,
        ) : SignerNotFound
    }
}
