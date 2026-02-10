package com.michaeltchuang.walletsdk.core.account.domain.model.core

import kotlinx.serialization.Serializable

@Serializable
sealed interface AccountRegistrationType {
    @Serializable
    data object Algo25 : AccountRegistrationType

    @Serializable
    data object LedgerBle : AccountRegistrationType

    @Serializable
    data object NoAuth : AccountRegistrationType

    @Serializable
    data object HdKey : AccountRegistrationType

    @Serializable
    data object Falcon24 : AccountRegistrationType
}
