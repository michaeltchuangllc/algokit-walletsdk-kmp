package com.michaeltchuang.walletsdk.core.account.domain.model.local

data class AccountMnemonic(
    val words: List<String>,
    val type: AccountType,
) {
    sealed interface AccountType {
        data object Algo25 : AccountType

        data object HdKey : AccountType

        data object Falcon25 : AccountType

        data object Falcon24 : AccountType
    }
}
