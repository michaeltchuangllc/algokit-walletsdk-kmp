package com.michaeltchuang.walletsdk.core.account.domain.model.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface AccountRegistrationType {
    @Serializable
    @SerialName("Algo25")
    data object Algo25 : AccountRegistrationType

    @Serializable
    @SerialName("LedgerBle")
    data object LedgerBle : AccountRegistrationType

    @Serializable
    @SerialName("NoAuth")
    data object NoAuth : AccountRegistrationType

    @Serializable
    @SerialName("HdKey")
    data object HdKey : AccountRegistrationType

    @Serializable
    @SerialName("Falcon25")
    data object Falcon25 : AccountRegistrationType

    @Serializable
    @SerialName("Falcon24")
    data object Falcon24 : AccountRegistrationType

    @Serializable
    @SerialName("SeedVault")
    data object SeedVault : AccountRegistrationType
}
