package com.michaeltchuang.walletsdk.core.network.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class AccountFastLookupResponse(
    @SerialName("algo_value")
    val algoValue: String?,

    @SerialName("usd_value")
    val usdValue: String?,

    @SerialName("calculation_type")
    val calculationType: String?,

    @SerialName("account_exists")
    val accountExists: Boolean?
)
